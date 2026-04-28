package com.bdic.admin;

import com.bdic.model.DocumentRequest;
import com.bdic.model.EncryptedData;
import com.bdic.model.LoginRequest;
import com.bdic.model.NetworkMessage;
import com.bdic.model.ServerResponse;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

/**
 * 负责封装客户端与服务端之间的协议请求与响应读取。
 */
public class DocumentServiceClient {

    /** 写给服务端的对象流，所有请求都通过它发送 NetworkMessage。 */
    private final ObjectOutputStream out;
    /** 从服务端读取响应的对象流。 */
    private final ObjectInputStream in;

    /**
     * 绑定已经完成 TLS 握手的对象输入输出流。
     */
    public DocumentServiceClient(ObjectOutputStream out, ObjectInputStream in) {
        this.out = out;
        this.in = in;
    }

    /** 发送登录请求并返回服务端响应。 */
    public synchronized ServerResponse login(String username, String password) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LOGIN, new LoginRequest(username, password));
    }

    /** 发送注册请求并返回服务端响应。 */
    public synchronized ServerResponse register(String username, String password) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.REGISTER, new LoginRequest(username, password));
    }

    /** 通知服务端注销当前会话。 */
    public synchronized ServerResponse logout() throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LOGOUT, null);
    }

    /** 上传一份已经加密并生成索引的文档。 */
    public synchronized ServerResponse upload(EncryptedData data) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.UPLOAD, data);
    }

    /** 使用关键词陷门搜索服务端索引。 */
    public synchronized ServerResponse search(byte[] trapdoor) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.SEARCH, trapdoor);
    }

    /** 获取当前用户的文档摘要列表。 */
    public synchronized ServerResponse listDocuments() throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LIST_DOCUMENTS, null);
    }

    /** 下载当前用户指定文档的完整密文数据。 */
    public synchronized ServerResponse downloadDocument(String docId) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.DOWNLOAD_DOCUMENT, new DocumentRequest(docId));
    }

    /** 删除当前用户指定文档。 */
    public synchronized ServerResponse deleteDocument(String docId) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.DELETE_DOCUMENT, new DocumentRequest(docId));
    }

    /**
     * 将服务端 data 字段转换成加密文档列表。
     *
     * <p>对象流反序列化后泛型会被擦除，调用方确认响应类型后再使用该方法。</p>
     */
    @SuppressWarnings("unchecked")
    public static List<EncryptedData> toEncryptedDataList(ServerResponse response) {
        return (List<EncryptedData>) response.getData();
    }

    /**
     * 按统一协议发送请求并同步等待服务端响应。
     */
    private ServerResponse sendAndRead(NetworkMessage.MessageType type, Object payload) throws Exception {
        // 该类的公开方法都加 synchronized，确保同一条 socket 上不会交叉写入请求。
        out.writeObject(new NetworkMessage(type, payload));
        out.flush();
        return readServerResponse();
    }

    /**
     * 读取并校验服务端响应消息。
     */
    private ServerResponse readServerResponse() throws Exception {
        NetworkMessage responseMessage = (NetworkMessage) in.readObject();
        Object payload = responseMessage.getPayload();
        if (!(payload instanceof ServerResponse)) {
            throw new IllegalStateException("Unexpected response payload: " + payload);
        }
        return (ServerResponse) payload;
    }
}
