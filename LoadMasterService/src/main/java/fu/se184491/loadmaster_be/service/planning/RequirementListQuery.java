package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.entity.planning.DeliveryRequirement;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.*;
import java.time.LocalDateTime;
import java.util.*;

/** Read-only list predicates mirror toResponse.shortDelivered; no persisted PARTIAL enum. */
public final class RequirementListQuery {
 private RequirementListQuery() {}
 private static final Set<String> SORTS=Set.of("id","destination","deadline","priority","packages","status","trip","createdAt");
 public static void validate(String status,String priority,LocalDateTime from,LocalDateTime to,Pageable page) {
  if(status!=null && !Set.of("PENDING","ASSIGNED","IN_TRIP","DELIVERED","PARTIAL").contains(status)) throw new IllegalArgumentException("Unsupported status");
  if(priority!=null) fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority.valueOf(priority);
  if(from!=null && to!=null && from.isAfter(to)) throw new IllegalArgumentException("Invalid deadline range");
  if(page.getPageSize()>100) throw new IllegalArgumentException("size must be <= 100");
  for(var o:page.getSort()) if(!SORTS.contains(o.getProperty())) throw new IllegalArgumentException("Unsupported sort field");
 }
 private static final String[] FOLD=folding();
 private static String[] folding(){
  StringBuilder a=new StringBuilder(),b=new StringBuilder();
  for(char c=0x00c0;c<=0x1eff;c++){
   if(!Character.isLowerCase(c)) continue;
   String n=java.text.Normalizer.normalize(String.valueOf(c),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","");
   if(n.length()==1 && n.charAt(0)>='a' && n.charAt(0)<='z'){a.append(c);b.append(n);}
  }
  a.append('\u0111');b.append('d');return new String[]{a.toString(),b.toString()};
 }
 private static Predicate contains(CriteriaBuilder cb,Expression<String> field,String term){
  var value=cb.function("translate",String.class,cb.lower(field),cb.literal(FOLD[0]),cb.literal(FOLD[1]));
  value=cb.function("regexp_replace",String.class,value,cb.literal("[\u0300-\u036f]"),cb.literal(""),cb.literal("g"));
  return cb.like(value,"%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%",'!');
 }
 public static Specification<DeliveryRequirement> spec(Long company,String search,String status,String priority,LocalDateTime from,LocalDateTime to,boolean exclusive,Sort sort){
  return (root,query,cb)->{
   var trip=root.join("trip",JoinType.LEFT);
   var customer=root.join("customer",JoinType.LEFT);
   var dropped=query.subquery(Long.class);var dp=dropped.from(CargoPackage.class);
   dropped.select(cb.literal(1L)).where(cb.equal(dp.get("droppedRequirementId"),root.get("id")));
   var returned=query.subquery(Long.class);var rr=returned.from(DeliveryRequirement.class);var rp=rr.join("packages");
   returned.select(cb.literal(1L)).where(cb.equal(rr.get("id"),root.get("id")),cb.equal(rp.get("status"),fu.se184491.loadmaster_be.constant.cargo.PackageStatus.RETURNED));
   Predicate partial=cb.and(cb.isNotNull(trip.get("id")),cb.or(cb.exists(dropped),cb.exists(returned)));
   Expression<Integer> statusRank=cb.<Integer>selectCase().when(partial,4)
    .when(cb.equal(root.get("status"),fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus.PENDING),0)
    .when(cb.equal(root.get("status"),fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus.ASSIGNED),1).otherwise(2);
   List<Predicate> filters=new ArrayList<>();filters.add(cb.equal(root.get("company").get("id"),company));
   if(status!=null) filters.add(status.equals("PARTIAL")?partial:status.equals("DELIVERED")?cb.disjunction():
    cb.and(cb.not(partial),cb.equal(root.get("status"),fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus.valueOf(status))));
   if(priority!=null) filters.add(cb.equal(root.get("priority"),fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority.valueOf(priority)));
   if(from!=null) filters.add(cb.greaterThanOrEqualTo(root.get("deadline"),from));
   if(to!=null) filters.add(exclusive?cb.lessThan(root.get("deadline"),to):cb.lessThanOrEqualTo(root.get("deadline"),to));
   if(search!=null&&!search.isBlank()){
    String normalized=java.text.Normalizer.normalize(search,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","").replace('\u0111','d').replace('\u0110','d').toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+"," ").trim();
    for(String term:normalized.split("\\s+")) filters.add(cb.or(
     contains(cb,((org.hibernate.query.criteria.JpaExpression<?>)root.get("id")).cast(String.class),term),
     contains(cb,root.get("destination"),term),contains(cb,root.get("note"),term),
     contains(cb,customer.get("name"),term),contains(cb,trip.get("tripCode"),term)));
   }
   if(query.getResultType()!=Long.class && query.getResultType()!=long.class){
    List<jakarta.persistence.criteria.Order> orders=new ArrayList<>();
    for(var o:sort){
     Expression<?> field;
     switch(o.getProperty()){
      case "trip"->field=trip.get("tripCode");
      case "status"->field=statusRank;
      case "priority"->field=cb.<Integer>selectCase()
       .when(cb.equal(root.get("priority"),fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority.LOW),0)
       .when(cb.equal(root.get("priority"),fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority.NORMAL),1)
       .when(cb.equal(root.get("priority"),fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority.HIGH),2).otherwise(3);
      case "packages"->{var count=query.subquery(Long.class);var r=count.from(DeliveryRequirement.class);var p=r.join("packages");count.select(cb.count(p)).where(cb.equal(r.get("id"),root.get("id")));field=count;}
      default->field=root.get(o.getProperty());
     }
     orders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(field),1).otherwise(0)));
     orders.add(o.isAscending()?cb.asc(field):cb.desc(field));
    }
    if(sort.getOrderFor("id")==null) orders.add(cb.asc(root.get("id")));
    query.orderBy(orders);
   }
   return cb.and(filters.toArray(Predicate[]::new));
  };
 }
}
