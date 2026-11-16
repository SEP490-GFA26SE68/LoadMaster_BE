package fu.se184491.loadmaster_be.service.cargo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackagePackingProfile;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackagePackingProfileRepository;
import fu.se184491.loadmaster_be.service.cargo.Impl.PackageServiceImpl;
import fu.se184491.loadmaster_be.service.import_.Impl.CsvExcelParserServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real serializer/parser/response mapper; repository and all business data are synthetic. */
class PackingProfileOrientationMappingTest {
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings={"0,2","0,1,2,3,4,5","1","2","LWH","WLH","LWH,WLH"})
    void requestEntityAndActualResponseMapperPreserveProfileText(String orientation) throws Exception {
        var mapper=new ObjectMapper();
        var input=PackageRequest.builder().orientation(orientation).uprightOnly(true).build();
        var decoded=mapper.readValue(mapper.writeValueAsString(input),PackageRequest.class);
        assertEquals(orientation,decoded.getOrientation());
        var profile=CargoPackagePackingProfile.builder().orientation(decoded.getOrientation()).uprightOnly(decoded.getUprightOnly()).build();
        var repository=mock(CargoPackagePackingProfileRepository.class);
        when(repository.findByCargoPackageId(1L)).thenReturn(Optional.of(profile));
        var service=mock(PackageServiceImpl.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"packingProfileRepository",repository);
        PackageResponse response=ReflectionTestUtils.invokeMethod(service,"mapToResponse",CargoPackage.builder().id(1L).build());
        assertNotNull(response);
        assertEquals(orientation,response.getOrientation());
        assertEquals(Boolean.TRUE,response.getUprightOnly());
        assertEquals(orientation,mapper.readTree(mapper.writeValueAsString(response)).path("orientation").asText(null));
    }
    @ParameterizedTest @ValueSource(strings={"0,2","0,1,2,3,4,5","LWH","LWH,WLH"})
    void xlsxParserPreservesOrientationExactly(String orientation) throws Exception {
        byte[] content;
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
            var sheet=book.createSheet("synthetic");
            String[] headers={"packageCode","lengthCm","widthCm","heightCm","weightKg","quantity","destination","orientation","uprightOnly","handlingClass"};
            String[] values={"SYNTHETIC-ONLY","40","30","25","10","1","SYNTHETIC-STOP",orientation,"true","STANDARD"};
            var header=sheet.createRow(0);var row=sheet.createRow(1);
            for(int i=0;i<headers.length;i++){header.createCell(i).setCellValue(headers[i]);row.createCell(i).setCellValue(values[i]);}
            book.write(bytes);content=bytes.toByteArray();
        }
        var parsed=new CsvExcelParserServiceImpl().parse(new MockMultipartFile("file","synthetic.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",content));
        assertEquals(1,parsed.size());assertEquals(orientation,parsed.get(0).orientation());
        assertEquals(Boolean.TRUE,parsed.get(0).uprightOnly());
    }
    @Test void orientationValidationCodeIsKnownToExistingDispatchDiagnostics(){
        var code=fu.se184491.loadmaster_be.exception.ErrorCode.valueOf("PACKING_PROFILE_ORIENTATION_INVALID");
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST,code.getHttpStatus());
    }
}