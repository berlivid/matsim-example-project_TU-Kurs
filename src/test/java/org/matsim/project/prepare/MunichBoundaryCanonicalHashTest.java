package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MunichBoundaryCanonicalHashTest {
    private static final String EXPECTED =
            "EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26";

    @Test
    void authoritativeBoundaryLfAndCrlfHaveTheExpectedCanonicalHash(
            @TempDir Path temp) throws Exception {
        String authoritative = Files.readString(
                MunichMunicipalBoundary.DEFAULT_FILE, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
        Path lf = temp.resolve("boundary-lf.json");
        Path crlf = temp.resolve("boundary-crlf.json");
        Files.writeString(lf, authoritative, StandardCharsets.UTF_8);
        Files.writeString(crlf, authoritative.replace("\n", "\r\n"),
                StandardCharsets.UTF_8);

        assertEquals(EXPECTED, MunichMunicipalBoundary.canonicalTextSha256(lf));
        assertEquals(EXPECTED, MunichMunicipalBoundary.canonicalTextSha256(crlf));
    }

    @Test
    void loneCarriageReturnsNormalizeToTheSameHash(@TempDir Path temp)
            throws Exception {
        String lfText = "{\n  \"type\": \"Polygon\",\n  \"coordinates\": []\n}\n";
        Path lf = temp.resolve("lf.json");
        Path cr = temp.resolve("cr.json");
        Files.writeString(lf, lfText, StandardCharsets.UTF_8);
        Files.writeString(cr, lfText.replace('\n', '\r'), StandardCharsets.UTF_8);
        assertEquals(MunichMunicipalBoundary.canonicalTextSha256(lf),
                MunichMunicipalBoundary.canonicalTextSha256(cr));
    }

    @Test
    void changingACoordinateChangesTheCanonicalHash(@TempDir Path temp)
            throws Exception {
        Path first = temp.resolve("first.json");
        Path second = temp.resolve("second.json");
        Files.writeString(first,
                "{\n\"type\":\"Polygon\",\n\"coordinates\":[[[1,2],[3,4]]]\n}\n",
                StandardCharsets.UTF_8);
        Files.writeString(second,
                "{\r\n\"type\":\"Polygon\",\r\n\"coordinates\":[[[1,2],[3,5]]]\r\n}\r\n",
                StandardCharsets.UTF_8);
        assertNotEquals(MunichMunicipalBoundary.canonicalTextSha256(first),
                MunichMunicipalBoundary.canonicalTextSha256(second));
    }

    @Test
    void changingNonLineEndingContentChangesTheCanonicalHash(@TempDir Path temp)
            throws Exception {
        Path polygon = temp.resolve("polygon.json");
        Path multipolygon = temp.resolve("multipolygon.json");
        Files.writeString(polygon, "{\n\"type\":\"Polygon\"\n}\n",
                StandardCharsets.UTF_8);
        Files.writeString(multipolygon, "{\r\n\"type\":\"MultiPolygon\"\r\n}\r\n",
                StandardCharsets.UTF_8);
        assertNotEquals(MunichMunicipalBoundary.canonicalTextSha256(polygon),
                MunichMunicipalBoundary.canonicalTextSha256(multipolygon));
    }

    @Test
    void authoritativeBoundaryObjectReportsExpectedCanonicalHash() throws Exception {
        assertEquals(EXPECTED, MunichMunicipalBoundary.loadDefault().sha256());
    }

    @Test
    void binaryHashingRemainsRawAndDoesNotNormalizeLineEndings(@TempDir Path temp)
            throws Exception {
        Path crlf = temp.resolve("binary-crlf.bin");
        Path lf = temp.resolve("binary-lf.bin");
        byte[] crlfBytes = new byte[]{0, 1, '\r', '\n', 2, (byte) 255};
        byte[] lfBytes = new byte[]{0, 1, '\n', 2, (byte) 255};
        Files.write(crlf, crlfBytes);
        Files.write(lf, lfBytes);

        assertEquals(rawSha256(crlfBytes),
                ValidateResidentModeChoiceCalibrationConfig.sha256(crlf));
        assertEquals(rawSha256(lfBytes),
                ValidateResidentModeChoiceCalibrationConfig.sha256(lf));
        assertNotEquals(ValidateResidentModeChoiceCalibrationConfig.sha256(crlf),
                ValidateResidentModeChoiceCalibrationConfig.sha256(lf));
    }

    @Test
    void protectedCompressedNetworkStillUsesItsRawExpectedHash() throws Exception {
        Path network = Path.of(
                "scenarios/munich_calibration_2019/input_transit/network-with-pt.xml.gz");
        assertEquals(
                "68FD7ABA2AD7DC459AC4B21672C426E0E4560994449BFE968278F0804CD6D86F",
                ValidateResidentModeChoiceCalibrationConfig.sha256(network));
    }

    @Test
    void residentValidatorAcceptsTheCanonicalBoundaryHash() throws Exception {
        ValidateResidentModeChoiceCalibrationConfig.loadAndValidateStructure(true);
    }

    private static String rawSha256(byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
