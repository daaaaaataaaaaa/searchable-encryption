package com.bdic.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 服务端统一响应对象。
 *
 * <p>success 表示操作是否成功，message 用于界面提示，data 承载具体业务结果。</p>
 */
public class ServerResponse implements Serializable {
    /** Java 序列化版本号，保证响应对象在对象流中保持兼容。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** 操作是否成功。 */
    private final boolean success;
    /** 展示给用户或调用方的说明信息。 */
    private final String message;
    /** 业务返回数据，例如会话信息、文档列表或下载的密文文档。 */
    private final Object data;

    /**
     * 构造服务端统一响应。
     */
    public ServerResponse(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    /** 返回操作是否成功。 */
    public boolean isSuccess() {
        return success;
    }

    /** 返回服务端消息。 */
    public String getMessage() {
        return message;
    }

    /** 返回业务数据负载。 */
    public Object getData() {
        return data;
    }
}
