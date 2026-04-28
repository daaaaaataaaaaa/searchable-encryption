package com.bdic.model;

import java.io.Serializable;

/**
 * 客户端与服务端之间传输的统一消息对象。
 *
 * <p>type 表示操作类型，payload 承载对应请求或响应数据。对象通过 TLS Socket 的对象流传输。</p>
 */
public class NetworkMessage implements Serializable {
    /** Java 序列化版本号，保证客户端和服务端对象流结构一致。 */
    private static final long serialVersionUID = 1L;

    /**
     * 协议消息类型。
     *
     * <p>客户端发送业务请求时使用除 RESPONSE 外的类型，服务端统一用 RESPONSE
     * 包装 {@link ServerResponse} 返回结果。</p>
     */
    public enum MessageType {
        // 用户认证相关操作。
        REGISTER,
        LOGIN,
        LOGOUT,

        // 文档上传和密文关键词搜索。
        UPLOAD,
        SEARCH,

        // 文档管理操作。
        LIST_DOCUMENTS,
        DOWNLOAD_DOCUMENT,
        DELETE_DOCUMENT,

        // 服务端统一响应。
        RESPONSE
    }

    /** 本条消息的业务类型。 */
    private MessageType type;
    /** 与类型对应的负载对象，例如 LoginRequest、EncryptedData 或 ServerResponse。 */
    private Object payload;

    /**
     * 构造一条协议消息。
     *
     * @param type 操作类型。
     * @param payload 对应操作需要携带的数据，可为空。
     */
    public NetworkMessage(MessageType type, Object payload) {
        this.type = type;
        this.payload = payload;
    }

    /** 返回消息类型。 */
    public MessageType getType() {
        return type;
    }

    /** 设置消息类型，通常仅反序列化或扩展协议时使用。 */
    public void setType(MessageType type) {
        this.type = type;
    }

    /** 返回消息负载。 */
    public Object getPayload() {
        return payload;
    }

    /** 设置消息负载。 */
    public void setPayload(Object payload) {
        this.payload = payload;
    }
}
