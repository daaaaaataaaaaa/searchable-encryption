package com.bdic.model;

import java.io.Serializable;
import java.util.List;

/**
 * 加密文档实体。
 *
 * <p>客户端上传时会填充文档元数据、DES 加密后的正文、加密后的关键词元数据，
 * 以及用于服务端搜索的关键词密文集合。</p>
 */
public class EncryptedData implements Serializable {
    /** Java 序列化版本号，保证客户端和服务端通过对象流传输时结构兼容。 */
    private static final long serialVersionUID = 1L;

    /** 用户可见的文档编号；服务端保存时会转换为内部存储 ID。 */
    private String docId;
    /** 原始文件名，用于列表展示和下载时恢复文件名。 */
    private String fileName;
    /** 文件 MIME 类型，辅助界面判断预览方式。 */
    private String mimeType;
    /** 简化后的媒体分类，例如 text、image、video、document、binary。 */
    private String mediaType;
    /** 原始文件大小，单位为字节。 */
    private long fileSize;
    /** 用 DES 加密后的关键词元数据，包含关键词和可选描述信息。 */
    private byte[] encryptedKeywordMetadata;
    /** 用 DES 加密后的文档正文或二进制内容。 */
    private byte[] encryptedContent;
    /** 用 PEKS 公钥生成的可搜索关键词密文集合。 */
    private List<byte[]> peksCiphertexts;

    /**
     * 兼容旧版测试和纯文本上传的简化构造器。
     *
     * <p>未显式传入的文件元数据会按文本文件默认值填充。</p>
     */
    public EncryptedData(String docId, byte[] encryptedContent, List<byte[]> peksCiphertexts) {
        this(docId, docId + ".txt", "text/plain", "text", encryptedContent == null ? 0 : encryptedContent.length, null, encryptedContent, peksCiphertexts);
    }

    /**
     * 构造完整的加密文档实体。
     *
     * @param docId 用户可见文档编号。
     * @param fileName 原始文件名。
     * @param mimeType 文件 MIME 类型。
     * @param mediaType 简化媒体分类。
     * @param fileSize 原始文件大小。
     * @param encryptedKeywordMetadata 加密后的关键词元数据。
     * @param encryptedContent 加密后的正文或二进制内容。
     * @param peksCiphertexts 关键词密文索引集合。
     */
    public EncryptedData(String docId, String fileName, String mimeType, String mediaType, long fileSize, byte[] encryptedKeywordMetadata, byte[] encryptedContent, List<byte[]> peksCiphertexts) {
        this.docId = docId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.encryptedKeywordMetadata = encryptedKeywordMetadata;
        this.encryptedContent = encryptedContent;
        this.peksCiphertexts = peksCiphertexts;
    }

    /** 返回用户可见文档编号。 */
    public String getDocId() {
        return docId;
    }

    /** 设置用户可见文档编号。 */
    public void setDocId(String docId) {
        this.docId = docId;
    }

    /** 返回 DES 加密后的文档内容。 */
    public byte[] getEncryptedContent() {
        return encryptedContent;
    }

    /** 设置 DES 加密后的文档内容。 */
    public void setEncryptedContent(byte[] encryptedContent) {
        this.encryptedContent = encryptedContent;
    }

    /** 返回原始文件名。 */
    public String getFileName() {
        return fileName;
    }

    /** 设置原始文件名。 */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /** 返回文件 MIME 类型。 */
    public String getMimeType() {
        return mimeType;
    }

    /** 设置文件 MIME 类型。 */
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    /** 返回简化媒体分类。 */
    public String getMediaType() {
        return mediaType;
    }

    /** 设置简化媒体分类。 */
    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    /** 返回原始文件大小，单位为字节。 */
    public long getFileSize() {
        return fileSize;
    }

    /** 设置原始文件大小，单位为字节。 */
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    /** 返回加密后的关键词元数据。 */
    public byte[] getEncryptedKeywordMetadata() {
        return encryptedKeywordMetadata;
    }

    /** 设置加密后的关键词元数据。 */
    public void setEncryptedKeywordMetadata(byte[] encryptedKeywordMetadata) {
        this.encryptedKeywordMetadata = encryptedKeywordMetadata;
    }

    /** 返回可搜索关键词密文集合。 */
    public List<byte[]> getPeksCiphertexts() {
        return peksCiphertexts;
    }

    /** 设置可搜索关键词密文集合，常用于重建索引后覆盖旧集合。 */
    public void setPeksCiphertexts(List<byte[]> peksCiphertexts) {
        this.peksCiphertexts = peksCiphertexts;
    }
}
