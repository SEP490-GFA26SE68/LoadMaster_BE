package fu.se184491.loadmaster_be.service.qr.Impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeServiceImpl implements QrCodeService {

    private static final int QR_SIZE_PX = 300;
    private static final String QR_FORMAT = "PNG";

    private final CargoPackageRepository cargoPackageRepository;

    // -------------------------------------------------------------------------
    // generateQrToken
    // -------------------------------------------------------------------------

    @Override
    public String generateQrToken() {
        String token;
        do {
            token = UUID.randomUUID().toString();
        } while (cargoPackageRepository.existsByQrToken(token));
        return token;
    }

    // -------------------------------------------------------------------------
    // generateQrPng
    // -------------------------------------------------------------------------

    @Override
    public byte[] generateQrPng(String qrToken) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(qrToken, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, QR_FORMAT, out);
            return out.toByteArray();

        } catch (WriterException | IOException e) {
            log.error("Failed to generate QR PNG for token: {}", qrToken, e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // lookupByToken
    // -------------------------------------------------------------------------

    @Override
    public CargoPackage lookupByToken(String qrToken) {
        return cargoPackageRepository
                .findByQrToken(qrToken)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // findById
    // -------------------------------------------------------------------------

    @Override
    public CargoPackage findById(Long id) {
        return cargoPackageRepository
                .findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_NOT_FOUND));
    }
}
