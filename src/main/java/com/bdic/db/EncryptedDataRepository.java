package com.bdic.db;

import com.bdic.crypto.PEKSUtil;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.HexFormat;
import java.util.Set;

/**
 * 加密文档仓储。
 *
 * <p>负责保存密文正文、维护关键词密文索引，并按当前用户隔离查询结果。对外暴露的 docId
 * 是用户输入的文档编号，数据库主键会额外混入用户名生成，避免不同用户使用相同 docId 时互相覆盖。</p>
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class EncryptedDataRepository {

    /** 数据库访问入口。 */
    private final DatabaseManager databaseManager;

    /** 注入数据库管理器，供仓储执行文档与索引操作。 */
    public EncryptedDataRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 保存或覆盖当前用户的文档，同时重建该文档的关键词索引。
     */
    public void save(String username, EncryptedData encryptedData) {
        String displayDocId = requireText(encryptedData.getDocId(), "Document ID");
        String storageDocId = toStorageDocId(username, displayDocId);

        // 使用 MySQL upsert 写文档主体：新文档插入，旧文档覆盖元数据和密文内容。
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String upsertDocumentSql = """
            INSERT INTO documents (doc_id, display_doc_id, owner_username, file_name, mime_type, media_type, file_size, encrypted_keyword_metadata, encrypted_content)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                display_doc_id = VALUES(display_doc_id),
                owner_username = VALUES(owner_username),
                file_name = VALUES(file_name),
                mime_type = VALUES(mime_type),
                media_type = VALUES(media_type),
                file_size = VALUES(file_size),
                encrypted_keyword_metadata = VALUES(encrypted_keyword_metadata),
                encrypted_content = VALUES(encrypted_content)
            """;
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String deleteKeywordsSql = "DELETE FROM keyword_index WHERE doc_id = ?";
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String insertKeywordSql = "INSERT INTO keyword_index (doc_id, peks_ciphertext) VALUES (?, ?)";

        try (Connection connection = databaseManager.getConnection()) {
            // 文档主体和关键词索引必须同成功同失败，因此显式开启事务。
            connection.setAutoCommit(false);

            try (PreparedStatement upsertDocument = connection.prepareStatement(upsertDocumentSql);
                 PreparedStatement deleteKeywords = connection.prepareStatement(deleteKeywordsSql);
                 PreparedStatement insertKeyword = connection.prepareStatement(insertKeywordSql)) {
                // 先保存文档主体，包括用户可见 ID、文件元数据和加密内容。
                upsertDocument.setString(1, storageDocId);
                upsertDocument.setString(2, displayDocId);
                upsertDocument.setString(3, username);
                upsertDocument.setString(4, encryptedData.getFileName());
                upsertDocument.setString(5, encryptedData.getMimeType());
                upsertDocument.setString(6, encryptedData.getMediaType());
                upsertDocument.setLong(7, encryptedData.getFileSize());
                upsertDocument.setBytes(8, encryptedData.getEncryptedKeywordMetadata());
                upsertDocument.setBytes(9, encryptedData.getEncryptedContent());
                upsertDocument.executeUpdate();

                // 文档更新时先删除旧索引，再写入新关键词密文，保证搜索结果与最新内容一致。
                deleteKeywords.setString(1, storageDocId);
                deleteKeywords.executeUpdate();

                List<byte[]> ciphertexts = encryptedData.getPeksCiphertexts() == null
                        ? List.of()
                        : encryptedData.getPeksCiphertexts();
                // 关键词密文可能很多，使用 batch 减少数据库往返次数。
                for (byte[] peksCiphertext : ciphertexts) {
                    insertKeyword.setString(1, storageDocId);
                    insertKeyword.setBytes(2, peksCiphertext);
                    insertKeyword.addBatch();
                }
                insertKeyword.executeBatch();

                // 所有步骤成功后提交事务，保证客户端搜索能看到完整一致的索引。
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save encrypted document", e);
        }
    }

    /**
     * 根据客户端传来的陷门搜索当前用户可访问的密文文档。
     */
    public List<EncryptedData> searchByTrapdoor(String username, byte[] trapdoor) {
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String searchSql = """
            SELECT d.doc_id AS storage_doc_id,
                   k.peks_ciphertext
            FROM documents d
            JOIN keyword_index k ON d.doc_id = k.doc_id
            WHERE d.owner_username = ?
            ORDER BY d.created_at DESC, d.display_doc_id ASC, k.id ASC
            """;
        Set<String> matchedStorageDocIds = new LinkedHashSet<>();

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(searchSql)) {
            // 服务端只拿陷门和索引密文执行 PEKS 测试，不需要解开明文关键词。
            statement.setString(1, username);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    byte[] peksCiphertext = resultSet.getBytes("peks_ciphertext");
                    if (PEKSUtil.test(peksCiphertext, trapdoor)) {
                        matchedStorageDocIds.add(resultSet.getString("storage_doc_id"));
                    }
                }
            }

            // 搜索阶段按内部 ID 二次加载结果，便于控制是否携带大体积正文。
            return loadDocumentsByStorageIds(connection, username, new ArrayList<>(matchedStorageDocIds), true);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search encrypted documents", e);
        }
    }

    /**
     * 获取当前用户的文档摘要列表，不返回密文正文。
     */
    public List<DocumentSummary> listDocuments(String username) {
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            SELECT COALESCE(d.display_doc_id, d.doc_id) AS display_doc_id,
                   d.file_name,
                   d.media_type,
                   d.file_size,
                   d.created_at,
                   COUNT(k.id) AS keyword_count
            FROM documents d
            LEFT JOIN keyword_index k ON d.doc_id = k.doc_id
            WHERE d.owner_username = ?
            GROUP BY d.doc_id, d.display_doc_id, d.file_name, d.media_type, d.file_size, d.created_at
            ORDER BY d.created_at DESC, display_doc_id ASC
            """;

        List<DocumentSummary> summaries = new ArrayList<>();

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    // created_at 可能来自历史数据或测试数据，转 LocalDateTime 前先判空。
                    Timestamp createdAt = resultSet.getTimestamp("created_at");
                    LocalDateTime time = createdAt == null ? null : createdAt.toLocalDateTime();
                    summaries.add(new DocumentSummary(
                            resultSet.getString("display_doc_id"),
                            resultSet.getString("file_name"),
                            resultSet.getString("media_type"),
                            resultSet.getLong("file_size"),
                            resultSet.getInt("keyword_count"),
                            time
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list documents", e);
        }

        return summaries;
    }

    /**
     * 根据用户可见 docId 查询完整密文文档，用于下载、重建索引等操作。
     */
    public EncryptedData findByOwnerAndDocId(String username, String docId) {
        String displayDocId = requireText(docId, "Document ID");
        String storageDocId = toStorageDocId(username, displayDocId);
        // 兼容新旧 ID：优先用用户名哈希后的内部 ID，也接受历史直接保存的 docId。
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            SELECT doc_id AS storage_doc_id,
                   COALESCE(display_doc_id, doc_id) AS display_doc_id,
                   file_name,
                   mime_type,
                   media_type,
                   file_size,
                   encrypted_keyword_metadata,
                   encrypted_content
            FROM documents
            WHERE owner_username = ?
              AND (doc_id = ? OR display_doc_id = ? OR doc_id = ?)
            ORDER BY CASE WHEN doc_id = ? THEN 0 ELSE 1 END
            LIMIT 1
            """;

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, storageDocId);
            statement.setString(3, displayDocId);
            statement.setString(4, displayDocId);
            statement.setString(5, storageDocId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return mapDocument(connection, resultSet, resultSet.getString("storage_doc_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load document details", e);
        }
    }

    /**
     * 删除当前用户指定文档。删除文档会通过外键级联删除关键词索引。
     */
    public boolean deleteByOwnerAndDocId(String username, String docId) {
        String displayDocId = requireText(docId, "Document ID");
        String storageDocId = toStorageDocId(username, displayDocId);
        // 删除文档主体即可，keyword_index 通过外键 ON DELETE CASCADE 自动清理。
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            DELETE FROM documents
            WHERE owner_username = ?
              AND (doc_id = ? OR display_doc_id = ? OR doc_id = ?)
            """;

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, storageDocId);
            statement.setString(3, displayDocId);
            statement.setString(4, displayDocId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete document", e);
        }
    }

    /**
     * 生成数据库内部文档 ID。用户名参与哈希，保证多用户同名文档彼此隔离。
     */
    public static String toStorageDocId(String username, String displayDocId) {
        String normalizedUsername = requireText(username, "Username");
        String normalizedDocId = requireText(displayDocId, "Document ID");
        // 使用用户名和展示用文档 ID 共同生成稳定哈希，避免不同用户间的主键冲突。
        byte[] input = (normalizedUsername + "\u0000" + normalizedDocId).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "doc-" + HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * 将结果集的一行转换为可传输给客户端的文档对象。
     */
    private EncryptedData mapDocument(Connection connection, ResultSet resultSet, String storageDocId) throws SQLException {
        // 完整文档会带回加密正文和关键词密文，主要用于下载或重建索引。
        return new EncryptedData(
                resultSet.getString("display_doc_id"),
                resultSet.getString("file_name"),
                resultSet.getString("mime_type"),
                resultSet.getString("media_type"),
                resultSet.getLong("file_size"),
                resultSet.getBytes("encrypted_keyword_metadata"),
                resultSet.getBytes("encrypted_content"),
                findKeywordsByStorageDocId(connection, storageDocId)
        );
    }

    /**
     * 搜索结果只返回界面展示真正需要的数据。
     * 对图片/音视频等二进制文件，不回传整份加密内容，避免一次搜索把大量 blob 推到 socket 上。
     */
    private EncryptedData mapSearchDocument(ResultSet resultSet) throws SQLException {
        String mediaType = resultSet.getString("media_type");
        boolean includeEncryptedContent = isTextMediaType(mediaType);
        // 文本搜索结果可直接预览，二进制搜索结果只返回元数据以节省网络和内存。
        return new EncryptedData(
                resultSet.getString("display_doc_id"),
                resultSet.getString("file_name"),
                resultSet.getString("mime_type"),
                mediaType,
                resultSet.getLong("file_size"),
                resultSet.getBytes("encrypted_keyword_metadata"),
                includeEncryptedContent ? resultSet.getBytes("encrypted_content") : null,
                List.of()
        );
    }

    /**
     * 根据内部文档 ID 列表读取完整文档详情，避免搜索阶段直接把所有密文正文扫入内存。
     */
    private List<EncryptedData> loadDocumentsByStorageIds(Connection connection, String username, List<String> storageDocIds, boolean lightweightSearchResult) throws SQLException {
        if (storageDocIds.isEmpty()) {
            return List.of();
        }

        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            SELECT doc_id AS storage_doc_id,
                   COALESCE(display_doc_id, doc_id) AS display_doc_id,
                   file_name,
                   mime_type,
                   media_type,
                   file_size,
                   encrypted_keyword_metadata,
                   encrypted_content
            FROM documents
            WHERE owner_username = ?
              AND doc_id = ?
            LIMIT 1
            """;

        List<EncryptedData> documents = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String storageDocId : storageDocIds) {
                // 逐个 ID 加载以保持 searchSql 返回的排序，并避免拼接动态 IN 子句。
                statement.setString(1, username);
                statement.setString(2, storageDocId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        documents.add(lightweightSearchResult
                                ? mapSearchDocument(resultSet)
                                : mapDocument(connection, resultSet, storageDocId));
                    }
                }
            }
        }
        return documents;
    }

    /**
     * 读取某个文档下的全部关键词密文。
     */
    private List<byte[]> findKeywordsByStorageDocId(Connection connection, String storageDocId) throws SQLException {
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = "SELECT peks_ciphertext FROM keyword_index WHERE doc_id = ?";
        List<byte[]> ciphertexts = new ArrayList<>();

        // 下载完整文档或重建索引时需要把已有关键词密文一起带回客户端。
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storageDocId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ciphertexts.add(resultSet.getBytes("peks_ciphertext"));
                }
            }
        }

        return ciphertexts;
    }

    /** 判断搜索结果是否需要携带密文正文。 */
    private static boolean isTextMediaType(String mediaType) {
        return mediaType == null || mediaType.isBlank() || "text".equalsIgnoreCase(mediaType);
    }

    /**
     * 校验必填字符串，并统一去除首尾空白。
     */
    private static String requireText(String value, String fieldName) {
        // Objects.requireNonNull 先拦截 null，再统一 trim，保证 ID 和用户名没有首尾空白。
        String normalized = Objects.requireNonNull(value, fieldName + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }
}
