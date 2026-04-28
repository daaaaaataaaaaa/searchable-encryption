package com.bdic.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档摘要信息。
 *
 * <p>用于文档列表展示，只包含元数据，不包含加密正文。</p>
 */
public class DocumentSummary implements Serializable {
    /** Java 序列化版本号，保证客户端和服务端通过对象流传输时结构兼容。 */
    private static final long serialVersionUID = 1L;

    /** 用户可见的文档编号。 */
    private final String docId;
    /** 原始文件名或纯文本上传时生成的默认文件名。 */
    private final String fileName;
    /** 文件 MIME 类型，例如 text/plain、image/png。 */
    private final String mediaType;
    /** 原始文件大小，单位为字节。 */
    private final long fileSize;
    /** 该文档在关键词索引表中的密文关键词数量。 */
    private final int keywordCount;
    /** 文档首次写入数据库的时间。 */
    private final LocalDateTime createdAt;

    /**
     * 构造用于文档列表展示的摘要对象。
     */
    public DocumentSummary(String docId, String fileName, String mediaType, long fileSize, int keywordCount, LocalDateTime createdAt) {
        this.docId = docId;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.keywordCount = keywordCount;
        this.createdAt = createdAt;
    }

    /**
     * 返回用户可见的文档编号。
     */
    public String getDocId() {
        return docId;
    }

    /**
     * 返回该文档的索引关键词数量。
     */
    public int getKeywordCount() {
        return keywordCount;
    }

    /**
     * 返回原始文件名。
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * 返回面向界面展示的媒体类型分类。
     */
    public String getMediaType() {
        return mediaType;
    }

    /**
     * 返回原始文件大小，单位为字节。
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * 返回文档创建时间。
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
