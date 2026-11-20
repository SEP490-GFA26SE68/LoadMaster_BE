ALTER TABLE unplaced_packages
DROP CONSTRAINT unplaced_packages_rejection_reason_check;

ALTER TABLE unplaced_packages
    ADD CONSTRAINT unplaced_packages_rejection_reason_check
        CHECK (
            rejection_reason IN (
                                 'OVER_WEIGHT',
                                 'OUT_OF_SPACE',
                                 'STACKING_CONFLICT',
                                 'LIFO_BLOCK',
                                 'CONSTRAINT_VIOLATED'
                )
            );