package ee.cyber.cdoc20.container;

import ee.cyber.cdoc20.client.model.Capsule;
import ee.cyber.cdoc20.container.recipients.EccRecipient;
import ee.cyber.cdoc20.container.recipients.EccServerKeyRecipient;
import ee.cyber.cdoc20.container.recipients.RSAServerKeyRecipient;
import ee.cyber.cdoc20.container.recipients.Recipient;
import ee.cyber.cdoc20.crypto.ECKeys;
import ee.cyber.cdoc20.crypto.PemTools;
import ee.cyber.cdoc20.crypto.RsaUtils;
import ee.cyber.cdoc20.fbs.header.Header;
import ee.cyber.cdoc20.fbs.header.RecipientRecord;
import ee.cyber.cdoc20.fbs.recipients.RSAPublicKeyDetails;
import ee.cyber.cdoc20.client.ExtApiException;
import ee.cyber.cdoc20.client.KeyCapsuleClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import ee.cyber.cdoc20.client.KeyCapsuleClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import static ee.cyber.cdoc20.fbs.header.Details.recipients_RSAPublicKeyDetails;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class EnvelopeTest {
    private static final Logger log = LoggerFactory.getLogger(EnvelopeTest.class);

    @SuppressWarnings("checkstyle:OperatorWrap")
    private final String bobKeyPem = "-----BEGIN EC PRIVATE KEY-----\n" +
            "MIGkAgEBBDAFxoHAdX8mU9cjiXOy46Gljmongxto0nHwRQs5cb93vIcysAaYLmhL\n" +
            "mH4DPqnSXJWgBwYFK4EEACKhZANiAAR5Yacpp5H4aBAIxkDtdBXcw/BFyMNEQu4B\n" +
            "LqnEv1cUVHROnhw3hAW63F3H2PI93ZzB/BT6+C+gOLt3XkCT/H3C9X1ZktCd5lS2\n" +
            "BmC8zN4UciwrTb68gt4ylKUCd5g30KY=\n" +
            "-----END EC PRIVATE KEY-----\n";

    @Mock
    KeyCapsuleClient capsuleClientMock;

    Capsule capsuleData;

    // Mainly flatbuffers and friends
    @Test
    void testHeaderSerializationParse() throws Exception {

        KeyPair recipientKeyPair = PemTools.loadKeyPair(bobKeyPem);


        File payloadFile = new File(System.getProperty("java.io.tmpdir"), "payload-" + UUID.randomUUID() + ".txt");
        payloadFile.deleteOnExit();
        try (FileOutputStream payloadFos = new FileOutputStream(payloadFile)) {
            payloadFos.write("payload".getBytes(StandardCharsets.UTF_8));
        }

        ECPublicKey recipientPubKey = (ECPublicKey) recipientKeyPair.getPublic();

        Envelope envelope = Envelope.prepare(Map.of(recipientKeyPair.getPublic(), "testHeaderSerializationParse"),
                null);
        ByteArrayOutputStream dst = new ByteArrayOutputStream();
        envelope.encrypt(List.of(payloadFile), dst);

        byte[] resultBytes = dst.toByteArray();

        assertTrue(resultBytes.length > 0);

        ByteArrayOutputStream headerOs = new ByteArrayOutputStream();

        //no exception is also good indication that parsing worked
        List<Recipient> details = Envelope.parseHeader(new ByteArrayInputStream(resultBytes), headerOs);

        assertEquals(1, details.size());
        assertInstanceOf(EccRecipient.class, details.get(0));

        assertEquals(recipientPubKey, ((EccRecipient)details.get(0)).getRecipientPubKey());
        assertNotNull(details.get(0).getRecipientPubKeyLabel());
        assertTrue(details.get(0).getRecipientPubKeyLabel().startsWith("testHeaderSerializationParse"));
    }

    @Test
    void testRsaSerialization(@TempDir Path tempDir) throws Exception {

        UUID uuid = UUID.randomUUID();
        String payloadFileName = "payload-" + uuid + ".txt";
        String payloadData = "payload-" + uuid;
        File payloadFile = tempDir.resolve(payloadFileName).toFile();

        try (FileOutputStream payloadFos = new FileOutputStream(payloadFile)) {
            payloadFos.write(payloadData.getBytes(StandardCharsets.UTF_8));
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstanceStrong());

        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        Envelope envelope = Envelope.prepare(Map.of(publicKey, "testRsaSerialization"), null);

        ByteArrayOutputStream dst = new ByteArrayOutputStream();
        envelope.encrypt(List.of(payloadFile), dst);

        byte[] cdocBytes = dst.toByteArray();

        assertTrue(cdocBytes.length > 0);

        log.debug("available: {}", cdocBytes.length);

        Header header = Envelope.parseHeaderFBS(new ByteArrayInputStream(cdocBytes), null);

        assertNotNull(header);
        assertEquals(1, header.recipientsLength());

        RecipientRecord recipient = header.recipients(0);

        String keyLabelOut = recipient.keyLabel();
        assertEquals("testRsaSerialization", keyLabelOut);
        assertEquals(recipient.detailsType(), recipients_RSAPublicKeyDetails);

        RSAPublicKeyDetails rsaDetails = (RSAPublicKeyDetails) recipient.details(new RSAPublicKeyDetails());
        assertNotNull(rsaDetails);

        ByteBuffer rsaPubKeyBuf = rsaDetails.recipientPublicKeyAsByteBuffer();
        assertNotNull(rsaPubKeyBuf);
        byte[] rsaPubKeyBytes = Arrays.copyOfRange(rsaPubKeyBuf.array(), rsaPubKeyBuf.position(), rsaPubKeyBuf.limit());
        PublicKey publicKeyOut = RsaUtils.decodeRsaPubKey(rsaPubKeyBytes);

        assertEquals(publicKey, publicKeyOut);
    }

    @Test
    void testEccServerSerialization(@TempDir Path tempDir) throws Exception {
        KeyPair recipientKeyPair = PemTools.loadKeyPair(bobKeyPem);

        UUID uuid = UUID.randomUUID();
        String payloadFileName = "payload-" + uuid + ".txt";
        String payloadData = "payload-" + uuid;
        File payloadFile = tempDir.resolve(payloadFileName).toFile();

        try (FileOutputStream payloadFos = new FileOutputStream(payloadFile)) {
            payloadFos.write(payloadData.getBytes(StandardCharsets.UTF_8));
        }

        ECPublicKey recipientPubKey = (ECPublicKey) recipientKeyPair.getPublic();
        final String recipientKeyLabel = "testEccServerSerialization";

        when(capsuleClientMock.getServerIdentifier()).thenReturn("mock");
        when(capsuleClientMock.storeCapsule(any())).thenReturn("SD1234567890");

        Envelope envelope = Envelope.prepare(
            Map.of(recipientPubKey, recipientKeyLabel),
            capsuleClientMock
        );
        ByteArrayOutputStream dst = new ByteArrayOutputStream();
        envelope.encrypt(List.of(payloadFile), dst);

        byte[] resultBytes = dst.toByteArray();

        assertTrue(resultBytes.length > 0);

        //no exception is also good indication that parsing worked
        List<Recipient> eccRecipients =
                Envelope.parseHeader(new ByteArrayInputStream(resultBytes), null);

        assertEquals(1, eccRecipients.size());

        assertInstanceOf(EccServerKeyRecipient.class, eccRecipients.get(0));

        EccServerKeyRecipient details = (EccServerKeyRecipient) eccRecipients.get(0);

        assertEquals(recipientPubKey, details.getRecipientPubKey());

        assertEquals("mock", details.getKeyServerId());
        assertEquals("SD1234567890", details.getTransactionId());
        assertEquals(recipientKeyLabel, details.getRecipientPubKeyLabel());
    }

    @Test
    void testRsaServerSerialization(@TempDir Path tempDir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstanceStrong());

        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        UUID uuid = UUID.randomUUID();
        String payloadFileName = "payload-" + uuid + ".txt";
        String payloadData = "payload-" + uuid;
        File payloadFile = tempDir.resolve(payloadFileName).toFile();

        try (FileOutputStream payloadFos = new FileOutputStream(payloadFile)) {
            payloadFos.write(payloadData.getBytes(StandardCharsets.UTF_8));
        }

        final String recipientKeyLabel = "testRsaServerSerialization";

        when(capsuleClientMock.getServerIdentifier()).thenReturn("mock_rsa");
        when(capsuleClientMock.storeCapsule(any())).thenReturn("KC1234567890123456789012");

        Envelope envelope = Envelope.prepare(
                Map.of(publicKey, recipientKeyLabel),
            capsuleClientMock
        );
        ByteArrayOutputStream dst = new ByteArrayOutputStream();
        envelope.encrypt(List.of(payloadFile), dst);

        byte[] resultBytes = dst.toByteArray();

        assertTrue(resultBytes.length > 0);

        //no exception is also good indication that parsing worked
        List<Recipient> recipients =
                Envelope.parseHeader(new ByteArrayInputStream(resultBytes), null);

        assertEquals(1, recipients.size());

        assertInstanceOf(RSAServerKeyRecipient.class, recipients.get(0));

        RSAServerKeyRecipient details = (RSAServerKeyRecipient) recipients.get(0);

        assertEquals(publicKey, details.getRecipientPubKey());

        assertEquals("mock_rsa", details.getKeyServerId());
        assertEquals("KC1234567890123456789012", details.getTransactionId());
        assertEquals(recipientKeyLabel, details.getRecipientPubKeyLabel());

    }

    @Test
    void testECContainer(@TempDir Path tempDir) throws Exception {
        KeyPair bobKeyPair = PemTools.loadKeyPair(bobKeyPem);
        testContainer(tempDir, bobKeyPair, "testECContainer", null);
    }

    @Test
    void testECServerScenario(@TempDir Path tempDir) throws Exception {
        KeyPair keyPair = PemTools.loadKeyPair(bobKeyPem);
        String transactionId = "KC1234567890123456789011";

        when(capsuleClientMock.getServerIdentifier()).thenReturn("mock_ec_server");

        doAnswer(invocation -> {
            capsuleData = (Capsule) invocation.getArguments()[0];
            log.debug("storing capsule {}", capsuleData);
            return transactionId;
        }).when(capsuleClientMock).storeCapsule(any(Capsule.class));

        when(capsuleClientMock.getCapsule(transactionId)).thenAnswer((Answer<Optional<Capsule>>) invocation -> {
            log.debug("returning capsule {}", capsuleData);
            return Optional.of(capsuleData);
        });

        testContainer(tempDir, keyPair, "testECContainer", capsuleClientMock);

        verify(capsuleClientMock, times(1)).storeCapsule(any());
        verify(capsuleClientMock, times(1)).getCapsule(transactionId);

        assertEquals(Capsule.CapsuleTypeEnum.ECC_SECP384R1, capsuleData.getCapsuleType());
        assertEquals(keyPair.getPublic(), ECKeys.EllipticCurve.secp384r1.decodeFromTls(
                ByteBuffer.wrap(capsuleData.getRecipientId())));
        assertTrue(ECKeys.EllipticCurve.secp384r1.isValidKey(ECKeys.EllipticCurve.secp384r1.decodeFromTls(
                ByteBuffer.wrap(capsuleData.getEphemeralKeyMaterial()))));
    }

    @Test
    void testContainerUsingRSAKey(@TempDir Path tempDir) throws Exception {

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstanceStrong());
        KeyPair rsaKeyPair = generator.generateKeyPair();

        testContainer(tempDir, rsaKeyPair, "testContainerUsingRSAKey", null);
    }

    @Test
    void testRsaServerScenario(@TempDir Path tempDir) throws Exception {

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstanceStrong());
        KeyPair rsaKeyPair = generator.generateKeyPair();

        String transactionId = "KC1234567890123456789012";

        when(capsuleClientMock.getServerIdentifier()).thenReturn("mock_rsa_server");

        doAnswer(invocation -> {
            capsuleData = (Capsule) invocation.getArguments()[0];
            log.debug("storing capsule {}", capsuleData);
            return transactionId;
        }).when(capsuleClientMock).storeCapsule(any(Capsule.class));

        when(capsuleClientMock.getCapsule(transactionId)).thenAnswer((Answer<Optional<Capsule>>) invocation -> {
            log.debug("returning capsule {}", capsuleData);
            return Optional.of(capsuleData);
        });

        testContainer(tempDir, rsaKeyPair, "testContainerUsingRSAKey", capsuleClientMock);

        verify(capsuleClientMock, times(1)).storeCapsule(any());
        verify(capsuleClientMock, times(1)).getCapsule(transactionId);
        assertEquals(Capsule.CapsuleTypeEnum.RSA, capsuleData.getCapsuleType());

        assertEquals(rsaKeyPair.getPublic(), RsaUtils.decodeRsaPubKey(capsuleData.getRecipientId()));
        assertEquals(((RSAPublicKey)rsaKeyPair.getPublic()).getModulus().bitLength(),
                capsuleData.getEphemeralKeyMaterial().length * 8);
    }


    @Test
    @DisplayName("Check that already created files are removed, when mac check in ChaCha20Poly1305 fails")
    void testContainerWrongPoly1305Mac(@TempDir Path tempDir) throws Exception {
        KeyPair bobKeyPair = PemTools.loadKeyPair(bobKeyPem);
        UUID uuid = UUID.randomUUID();
        String payloadFileName = "payload-" + uuid + ".txt";
        String payloadData = "payload-" + uuid;
        File payloadFile = tempDir.resolve(payloadFileName).toFile();

        byte[] bytes = new byte[1024];

        int bytesWanted = 8 * 1024;
        // create bigger file, so that payload file is written to disk, before MAC check
        File biggerFile = tempDir.resolve("biggerFile").toFile();
        try (OutputStream os = Files.newOutputStream(biggerFile.toPath())) {
            for (int i = 0; i <= bytesWanted; i++) {
                new Random().nextBytes(bytes);
                os.write(bytes);
            }
        }

        Path outDir = tempDir.resolve("testContainer-" + uuid);
        Files.createDirectories(outDir);

        byte[] cdocContainerBytes = createContainer(payloadFile, payloadData.getBytes(StandardCharsets.UTF_8),
                bobKeyPair.getPublic(), "testContainerWrongPoly1305Mac", List.of(biggerFile), null);

        log.debug("cdoc size: {}", cdocContainerBytes.length);

        //last 16 bytes are Poly1305 MAC, corrupt that
        cdocContainerBytes[cdocContainerBytes.length - 1] = (byte) 0xff;
        cdocContainerBytes[cdocContainerBytes.length - 2] = (byte) 0xfe;

        var ex = assertThrows(
            Exception.class,
            () -> checkContainerDecrypt(cdocContainerBytes, outDir,
                bobKeyPair, List.of(payloadFileName), payloadFileName, payloadData, null)
        );

        assertInstanceOf(javax.crypto.AEADBadTagException.class, ex.getCause());

        assertNotNull(outDir.toFile().listFiles());
        //extracted files were deleted
        assertTrue(Arrays.stream(outDir.toFile().listFiles()).toList().isEmpty());
    }


    /**
     * Creates payloadFile, adds payloadData to payloadFile and creates encrypted container for recipientPubKey
     * @param payloadFile input payload file to be created and added to container
     * @param payloadData data to be written to payloadFile
     * @param recipientPubKey created container can be decrypted with recipientPubKey private part
     * @param additionalFiles optional additional file to add
     * @param capsuleClient
     * @return created container as byte[]
     * @throws IOException if IOException happens
     * @throws GeneralSecurityException if GeneralSecurityException happens
     */
    public byte[] createContainer(File payloadFile, byte[] payloadData, PublicKey recipientPubKey, String label,
                                  @Nullable List<File> additionalFiles,
                                  @Nullable KeyCapsuleClient capsuleClient)
            throws IOException, GeneralSecurityException, ExtApiException {

        try (FileOutputStream payloadFos = new FileOutputStream(payloadFile)) {
            payloadFos.write(payloadData);
        }

        Map<PublicKey, String> recipients = Map.of(recipientPubKey, label);

        List<File> files = new LinkedList<>();
        files.add(payloadFile);
        if (additionalFiles != null) {
            files.addAll(additionalFiles);
        }

        byte[] cdocContainerBytes;
        Envelope senderEnvelope = Envelope.prepare(recipients, capsuleClient);
        try (ByteArrayOutputStream dst = new ByteArrayOutputStream()) {
            senderEnvelope.encrypt(files, dst);
            cdocContainerBytes = dst.toByteArray();
        }
        assertNotNull(cdocContainerBytes);
        assertTrue(cdocContainerBytes.length > 0);
        return cdocContainerBytes;
    }

    /**
     * Creates CDOC2 container in tempDir and encrypts/decrypts it with keyPair. If capsulesClient is provided, then
     * test server scenarios
     */
    public void testContainer(Path tempDir, KeyPair keyPair, String keyLabel,
                              @Nullable KeyCapsuleClient capsulesClient) throws Exception {

        UUID uuid = UUID.randomUUID();
        String payloadFileName = "payload-" + uuid + ".txt";
        String payloadData = "payload-" + uuid;
        File payloadFile = tempDir.resolve(payloadFileName).toFile();

        Path outDir = tempDir.resolve("testContainer-" + uuid);
        Files.createDirectories(outDir);

        byte[] cdocContainerBytes = createContainer(payloadFile,
                payloadData.getBytes(StandardCharsets.UTF_8), keyPair.getPublic(), keyLabel, null,
                capsulesClient);

        assertTrue(cdocContainerBytes.length > 0);

        checkContainerDecrypt(cdocContainerBytes, outDir, keyPair,
                List.of(payloadFileName), payloadFileName, payloadData, capsulesClient);
    }

    public void checkContainerDecrypt(byte[] cdocBytes, Path outDir, KeyPair recipientKeyPair,
                                      List<String> expectedFilesExtracted, String payloadFileName,
                                      String expectedPayloadData,
                                      KeyCapsuleClient capsulesClient)
                throws Exception {

        try (ByteArrayInputStream bis = new ByteArrayInputStream(cdocBytes)) {

            KeyCapsuleClientFactory clientFactory = (capsulesClient == null) ? null : new KeyCapsuleClientFactory() {
                @Override
                public KeyCapsuleClient getForId(String serverId) {
                    Objects.requireNonNull(serverId);
                    if ((capsulesClient != null)
                            && (serverId.equals(capsulesClient.getServerIdentifier()))) {
                        return capsulesClient;
                    }

                    log.warn("No KeyCapsulesClient for {}", serverId);
                    return null;
                }
            };

            List<String> filesExtracted = Envelope.decrypt(bis, recipientKeyPair, outDir, clientFactory);

            assertEquals(expectedFilesExtracted, filesExtracted);
            Path payloadPath = Path.of(outDir.toAbsolutePath().toString(), payloadFileName);

            assertEquals(expectedPayloadData, Files.readString(payloadPath));
        }
    }

    // test that near max size header can be created and parsed
    @Test
    @Tag("slow")
    void testLongHeader(@TempDir Path tempDir) throws Exception {

        UUID uuid = UUID.randomUUID();
        String payloadFileName = "A";

        String payloadData = "";

        File payloadFile = tempDir.resolve(payloadFileName).toFile();

        try (FileOutputStream payloadFos = new FileOutputStream(payloadFile)) {
            payloadFos.write(payloadData.getBytes(StandardCharsets.UTF_8));
        }

        Path outDir = tempDir.resolve("testContainer-" + uuid);
        Files.createDirectories(outDir);

        KeyPair bobKeyPair = PemTools.loadKeyPair(bobKeyPem);

        ECPublicKey bobPubKey = (ECPublicKey) bobKeyPair.getPublic();

        // Code to find the limit of max header
        int singleKeyLen = Envelope.prepare(Map.of(bobPubKey, "longHeader"), null)
            .serializeHeader().length;
        int twoKeyLen = Envelope.prepare(
                Map.of(
                    bobPubKey, "longHeader",
                    ECKeys.generateEcKeyPair(ECKeys.SECP_384_R_1).getPublic(), "longHeader"
                ), null
            )
            .serializeHeader().length;

        // Seems that FBS adds overhead for arrays, as recipient_length grows, if recipients number grows
        final int fbsOverhead = 4;
        int recipientLength = twoKeyLen - singleKeyLen + fbsOverhead;
        int emptyHeaderLen = singleKeyLen - recipientLength;

        log.debug("empty header len:{}, single recipient len {}",
            emptyHeaderLen, singleKeyLen - recipientLength
        );

        int maxRecipientsNum = (Envelope.MAX_HEADER_LEN - emptyHeaderLen) / recipientLength;

        log.debug("Generating: {} EC key pairs. {} < {}",
            maxRecipientsNum,
            maxRecipientsNum * recipientLength + emptyHeaderLen, Envelope.MAX_HEADER_LEN);
        assertTrue(maxRecipientsNum * recipientLength + emptyHeaderLen < Envelope.MAX_HEADER_LEN);

        Map<PublicKey, String> keyLabelMap = new HashMap<>();
        Instant start = Instant.now();
        for  (int i = 1; i < maxRecipientsNum; i++) {
            keyLabelMap.put(ECKeys.generateEcKeyPair(ECKeys.SECP_384_R_1).getPublic(), "longHeader");
        }
        keyLabelMap.put(bobPubKey, "_bob_key_");


        Instant end = Instant.now();
        log.debug("Generated {} EC keys in {}s", keyLabelMap.size(), end.getEpochSecond() - start.getEpochSecond());

        Instant prepareStart = Instant.now();
        Envelope senderEnvelope = Envelope.prepare(keyLabelMap, null);
        Instant prepareEnd = Instant.now();
        log.debug("Prepared {} EC sender keys in {}s", keyLabelMap.size(),
                prepareEnd.getEpochSecond() - prepareStart.getEpochSecond());

        Instant serializeStart = Instant.now();
        byte[] headerBuf = senderEnvelope.serializeHeader();
        Instant serializeEnd = Instant.now();
        log.debug("Recipients: {} header size: {}B in {}s", keyLabelMap.size(), headerBuf.length,
                serializeEnd.getEpochSecond() - serializeStart.getEpochSecond());

        //  test that serialization fails for oversize header
        keyLabelMap.put(ECKeys.generateEcKeyPair(ECKeys.SECP_384_R_1).getPublic(), "longHeader+1");
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            Envelope.prepare(keyLabelMap, null).serializeHeader());
        assertTrue(exception.getMessage().contains("Header serialization failed"));


        try (ByteArrayOutputStream dst = new ByteArrayOutputStream()) {
            senderEnvelope.encrypt(List.of(payloadFile), dst);
            byte[] cdocContainerBytes = dst.toByteArray();

            assertTrue(cdocContainerBytes.length > 0);

            log.debug("CDOC container with {} recipients and minimal payload is {}B. ", keyLabelMap.size() - 1,
                    cdocContainerBytes.length);

            try (ByteArrayInputStream bis = new ByteArrayInputStream(cdocContainerBytes)) {
                List<String> filesExtracted = Envelope.decrypt(bis, bobKeyPair, outDir);

                assertEquals(List.of(payloadFileName), filesExtracted);
                Path payloadPath = Path.of(outDir.toAbsolutePath().toString(), payloadFileName);

                assertEquals(payloadData, Files.readString(payloadPath));
            }
        }
    }
}
