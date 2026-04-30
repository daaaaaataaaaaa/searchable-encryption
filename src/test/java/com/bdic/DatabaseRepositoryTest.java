package com.bdic;

import com.bdic.crypto.PEKSUtil;
import com.bdic.db.DatabaseManager;
import com.bdic.db.EncryptedDataRepository;
import com.bdic.model.EncryptedData;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import junit.framework.TestCase;

import java.security.KeyPair;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 加密文档仓储的单元/集成测试。
 *
 * <p>不依赖数据库的 ID 生成测试总会执行；真实数据库往返测试需要显式开启
 * {@code -Dse.integration.db=true}。</p>
 */
public class DatabaseRepositoryTest extends TestCase {

    /**
     * 验证相同展示 docId 在不同用户下会生成不同的内部存储 ID。
     */
    public void testStorageDocumentIdsAreScopedByUser() {
        String aliceDoc = EncryptedDataRepository.toStorageDocId("alice", "shared-doc");
        String bobDoc = EncryptedDataRepository.toStorageDocId("bob", "shared-doc");

        assertFalse(aliceDoc.equals(bobDoc));
        assertTrue(aliceDoc.startsWith("doc-"));
        assertEquals(aliceDoc, EncryptedDataRepository.toStorageDocId("alice", "shared-doc"));
    }

    /**
     * 在开启数据库集成测试时，验证保存、搜索、列表和二进制轻量搜索结果。
     */
    public void testRepositoryRoundTripWhenDatabaseIntegrationIsEnabled() throws Exception {
        if (!Boolean.getBoolean("se.integration.db")) {
            // 默认跳过，避免普通单元测试必须依赖本地 MySQL。
            return;
        }

        // 数据库连接参数可通过系统属性覆盖，默认指向本地测试库。
        String host = System.getProperty("se.db.host", "localhost");
        int port = Integer.parseInt(System.getProperty("se.db.port", "3306"));
        String databaseName = System.getProperty("se.db.name", "searchable_encryption_test");
        String username = System.getProperty("se.db.user", "root");
        String password = System.getProperty("se.db.password", "123456ysy");
        String ownerUsername = "repository_test_user";

        try {
            // 初始化测试库和仓储对象，并清理上一次测试残留数据。
            DatabaseManager databaseManager = new DatabaseManager(host, port, databaseName, username, password);
            databaseManager.initialize();
            EncryptedDataRepository repository = new EncryptedDataRepository(databaseManager);
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-1");
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-2");
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-3");

            try (Connection connection = databaseManager.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM users WHERE username = '" + ownerUsername + "'");
                statement.executeUpdate(
                        "INSERT INTO users (username, password_hash, password_salt) VALUES ('" + ownerUsername + "', X'01', X'01')"
                );
            }

            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();

            // 构造三份测试文档：两份文本、一份图片类二进制文档。
            EncryptedData firstDocument = new EncryptedData(
                    "doc-1",
                    "encrypted-1".getBytes(),
                    encryptKeywords(peksKeyPair.getPublic(), "alpha", "beta")
            );

            EncryptedData secondDocument = new EncryptedData(
                    "doc-2",
                    "encrypted-2".getBytes(),
                    encryptKeywords(peksKeyPair.getPublic(), "gamma")
            );

            EncryptedData binaryDocument = new EncryptedData(
                    "doc-3",
                    "screenshot.png",
                    "image/png",
                    "image",
                    3,
                    null,
                    new byte[]{0x01, 0x02, 0x03},
                    encryptKeywords(peksKeyPair.getPublic(), "screenshot")
            );

            repository.save(ownerUsername, firstDocument);
            repository.save(ownerUsername, secondDocument);
            repository.save(ownerUsername, binaryDocument);

            // 分别验证完整关键词、前缀关键词和图片关键词搜索。
            byte[] trapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "alpha");
            List<EncryptedData> searchResults = repository.searchByTrapdoor(ownerUsername, trapdoor);
            byte[] prefixTrapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "alp");
            List<EncryptedData> prefixSearchResults = repository.searchByTrapdoor(ownerUsername, prefixTrapdoor);
            byte[] imageTrapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "screenshot");
            List<EncryptedData> imageSearchResults = repository.searchByTrapdoor(ownerUsername, imageTrapdoor);

            assertEquals(1, searchResults.size());
            assertEquals("doc-1", searchResults.get(0).getDocId());
            assertEquals(1, prefixSearchResults.size());
            assertEquals("doc-1", prefixSearchResults.get(0).getDocId());
            assertEquals(1, imageSearchResults.size());
            assertEquals("doc-3", imageSearchResults.get(0).getDocId());
            // 图片搜索结果为了节省网络开销，不携带完整 encryptedContent。
            assertNull(imageSearchResults.get(0).getEncryptedContent());
        } finally {
            // MySQL 驱动会启动清理线程，测试结束时显式关闭，避免进程悬挂。
            AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }

    /**
     * 按生产代码同样的前缀扩展规则生成关键词密文。
     */
    private static List<byte[]> encryptKeywords(PublicKey peksPublicKey, String... keywords) throws Exception {
        Set<String> tokens = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }

            String normalizedKeyword = keyword.trim().toLowerCase();
            tokens.add(normalizedKeyword);
            if (normalizedKeyword.length() <= 2) {
                continue;
            }

            // 支持前缀搜索：alpha 会额外生成 al、alp、alph。
            for (int i = 2; i < normalizedKeyword.length(); i++) {
                tokens.add(normalizedKeyword.substring(0, i));
            }
        }

        List<byte[]> encryptedKeywords = new ArrayList<>();
        for (String token : tokens) {
            // 服务端搜索时用查询陷门测试这些 PEKS 密文。
            encryptedKeywords.add(PEKSUtil.encrypt(peksPublicKey, token));
        }
        return encryptedKeywords;
    }
}
