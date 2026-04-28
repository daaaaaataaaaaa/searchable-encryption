package com.bdic.model;

import java.io.Serializable;

/**
 * 文档操作请求。
 *
 * <p>下载、删除、重建索引等操作只需要传递用户可见的 docId。</p>
 */
public class DocumentRequest implements Serializable {
    /** Java 序列化版本号，保证对象流反序列化时结构兼容。 */
    private static final long serialVersionUID = 1L;

    /** 用户界面上看到的文档编号，服务端会再转换成数据库内部 ID。 */
    private final String docId;

    /**
     * 创建只携带文档编号的请求对象。
     *
     * @param docId 用户输入或列表中选择的文档编号。
     */
    public DocumentRequest(String docId) {
        this.docId = docId;
    }

    /**
     * 返回待操作的用户可见文档编号。
     */
    public String getDocId() {
        return docId;
    }
}
