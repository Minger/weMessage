package scott.wemessage.server.connection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Type;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import scott.wemessage.commons.crypto.AESCrypto;
import scott.wemessage.commons.crypto.AESCrypto.CipherByteArrayIvMac;
import scott.wemessage.commons.json.action.JSONAction;
import scott.wemessage.commons.json.action.JSONResult;
import scott.wemessage.commons.json.connection.ClientMessage;
import scott.wemessage.commons.json.connection.InitConnect;
import scott.wemessage.commons.json.connection.ServerMessage;
import scott.wemessage.commons.json.message.JSONAttachment;
import scott.wemessage.commons.json.message.JSONMessage;
import scott.wemessage.commons.json.message.security.JSONEncryptedText;
import scott.wemessage.commons.types.ActionType;
import scott.wemessage.commons.types.DeviceType;
import scott.wemessage.commons.types.DisconnectReason;
import scott.wemessage.commons.types.ReturnType;
import scott.wemessage.commons.utils.ByteArrayAdapter;
import scott.wemessage.commons.utils.DateUtils;
import scott.wemessage.commons.utils.FileUtils;
import scott.wemessage.commons.utils.StringUtils;
import scott.wemessage.server.ServerLogger;
import scott.wemessage.server.commands.AppleScriptExecutor;
import scott.wemessage.server.configuration.ServerConfiguration;
import scott.wemessage.server.database.MessagesDatabase;
import scott.wemessage.server.events.EventManager;
import scott.wemessage.server.events.connection.ClientMessageReceivedEvent;
import scott.wemessage.server.messages.Handle;
import scott.wemessage.server.messages.Message;
import scott.wemessage.server.messages.chat.ChatBase;
import scott.wemessage.server.messages.chat.GroupChat;
import scott.wemessage.server.messages.chat.PeerChat;
import scott.wemessage.server.security.ServerBase64Wrapper;
import scott.wemessage.server.weMessage;

public class Device extends Thread {

    private final String TAG = "weServer Device Service";
    private final Object deviceManagerLock = new Object();
    private final Object socketLock = new Object();
    private final Object deviceIdLock = new Object();
    private final Object deviceTypeLock = new Object();
    private final Object inputStreamLock = new Object();
    private final Object outputStreamLock = new Object();

    private DeviceManager deviceManager;
    private DeviceType deviceType;
    private Socket socket;
    private ObjectInputStream inputStream;
    private ObjectOutputStream outputStream;
    private String deviceId;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private AtomicBoolean hasTriedVerifying = new AtomicBoolean(false);

    public Device(DeviceManager deviceManager, Socket socket){
        synchronized (deviceManagerLock) {
            this.deviceManager = deviceManager;
        }
        synchronized (socketLock) {
            this.socket = socket;
        }
    }

    public DeviceManager getDeviceManager(){
        synchronized (deviceManagerLock){
            return deviceManager;
        }
    }

    public Socket getSocket(){
        synchronized (socketLock) {
            return socket;
        }
    }

    public String getDeviceId(){
        synchronized (deviceIdLock){
            return deviceId;
        }
    }

    public String getAddress() {
        return getSocket().getInetAddress().getHostAddress();
    }

    public DeviceType getDeviceType() {
        synchronized (deviceTypeLock) {
            return deviceType;
        }
    }

    public ObjectInputStream getInputStream(){
        synchronized (inputStreamLock){
            return inputStream;
        }
    }

    public ObjectOutputStream getOutputStream(){
        synchronized (outputStreamLock){
            return outputStream;
        }
    }

    public ClientMessage getIncomingMessage(String prefix, Object incoming) throws IOException, ClassNotFoundException {
        String data = ((String) incoming).split(prefix)[1];
        return new GsonBuilder().registerTypeHierarchyAdapter(byte[].class, new ByteArrayAdapter(new ServerBase64Wrapper())).create().fromJson(data, ClientMessage.class);
    }

    public void sendOutgoingMessage(String prefix, Object outgoingData, Class<?> outgoingDataClass){
        try {
            Type type = TypeToken.get(outgoingDataClass).getType();
            String outgoingDataJson = new GsonBuilder().registerTypeHierarchyAdapter(byte[].class, new ByteArrayAdapter(new ServerBase64Wrapper())).create().toJson(outgoingData, type);
            ServerMessage serverMessage = new ServerMessage(UUID.randomUUID().toString(), outgoingDataJson);
            String outgoingJson = new Gson().toJson(serverMessage);

            getOutputStream().writeObject(prefix + outgoingJson);
            getOutputStream().flush();
        }catch(Exception ex){
            ServerLogger.error(TAG, "An error occurred while sending a message to Device: " + getAddress(), ex);
        }
    }

    public void sendOutgoingMessage(final Message message){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONMessage jsonMessage = message.toJson(getDeviceManager().getMessageServer().getConfiguration(), getDeviceManager().getMessageServer().getScriptExecutor());

                    sendOutgoingMessage(weMessage.JSON_NEW_MESSAGE, jsonMessage, JSONMessage.class);
                }catch (Exception ex){
                    ServerLogger.error(TAG, "An error occurred while trying to parse a message to JSON", ex);
                }
            }
        }).start();
    }

    public void sendOutgoingMessage(JSONMessage message) {
        sendOutgoingMessage(weMessage.JSON_NEW_MESSAGE, message, JSONMessage.class);
    }

    public void updateOutgoingMessage(final Message message){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONMessage jsonMessage = message.toJson(getDeviceManager().getMessageServer().getConfiguration(), getDeviceManager().getMessageServer().getScriptExecutor());

                    sendOutgoingMessage(weMessage.JSON_MESSAGE_UPDATED, jsonMessage, JSONMessage.class);
                }catch (Exception ex){
                    ServerLogger.error(TAG, "An error occurred while trying to parse a message to JSON", ex);
                }
            }
        }).start();
    }

    public List<Integer> relayIncomingMessage(JSONMessage message){
        MessagesDatabase messagesDb = getDeviceManager().getMessageServer().getMessagesDatabase();
        AppleScriptExecutor executor = getDeviceManager().getMessageServer().getScriptExecutor();

        try {
            String decryptedMessage = AESCrypto.decryptString(message.getEncryptedText().getEncryptedText(), message.getEncryptedText().getKey());

            List<File> attachments = new ArrayList<>();

            for (JSONAttachment a : message.getAttachments()){
                byte[] bytes = a.getFileData().getEncryptedData();
                String key = a.getFileData().getKey();
                String ivParam = a.getFileData().getIvParams();

                byte[] decryptedBytes = AESCrypto.decryptBytes(new CipherByteArrayIvMac(bytes, ivParam), key);
                File file = new File(executor.getTempFolder().toString(), a.getTransferName());

                if (!file.exists()){
                    file.createNewFile();
                }
                FileUtils.writeBytesToFile(file, decryptedBytes);
                attachments.add(file);
            }

            ChatBase chat = messagesDb.getChatByGuid(message.getChat().getMacGuid());

             if (chat == null) {
                if (message.getChat().getParticipants().size() < 2) {
                    Object result;
                    String firstArg = message.getChat().getParticipants().get(0);

                    if (attachments.isEmpty()) {
                        result = executor.runScript(ActionType.SEND_MESSAGE, new String[]{firstArg, "", decryptedMessage});
                    } else if (attachments.size() == 1) {
                        result = executor.runScript(ActionType.SEND_MESSAGE, new String[]{firstArg, attachments.get(0).getAbsolutePath(), decryptedMessage});
                    } else {
                        for (File file : attachments) {
                            executor.runScript(ActionType.SEND_MESSAGE, new String[]{firstArg, file.getAbsolutePath(), ""});
                        }
                        result = executor.runScript(ActionType.SEND_MESSAGE, new String[]{firstArg, "", decryptedMessage});
                    }

                    return parseResult(result);
                } else {
                    ArrayList<Integer> results = new ArrayList<>();
                    results.add(ReturnType.NULL_MESSAGE.getCode());
                    results.add(ReturnType.GROUP_CHAT_NOT_FOUND.getCode());

                    return results;
                }
            }

            Object result;
            if (chat instanceof PeerChat){
                String firstArg = ((PeerChat) chat).getPeer().getHandleID();

                if (attachments.isEmpty()){
                    result = executor.runScript(ActionType.SEND_MESSAGE, new String[]{ firstArg, "", decryptedMessage});
                }else if (attachments.size() == 1){
                    result = executor.runScript(ActionType.SEND_MESSAGE, new String[] { firstArg, attachments.get(0).getAbsolutePath(), decryptedMessage });
                }else {
                    for (File file : attachments){
                        executor.runScript(ActionType.SEND_MESSAGE, new String[] { firstArg, file.getAbsolutePath(), "" });
                    }
                    result = executor.runScript(ActionType.SEND_MESSAGE, new String[] { firstArg, "", decryptedMessage });
                }
            }else {
                GroupChat groupChat = (GroupChat) chat;

                List<String>participantDummyList = new ArrayList<>();
                Message lastMessage = messagesDb.getLastMessageFromChat(chat);
                String firstArg;
                String timeArgument;
                String lastMessageText;

                if (lastMessage == null) {
                    timeArgument = "";
                    lastMessageText = "";
                } else {
                    Date lastMessageDate = lastMessage.getModernDateSent();

                    if (lastMessage.getText() == null){
                        lastMessageText = "";
                    }else {
                        lastMessageText = lastMessage.getText();
                    }

                    if (DateUtils.isSameDay(Calendar.getInstance().getTime(), lastMessageDate)){
                        timeArgument = new SimpleDateFormat("hh:mm a").format(lastMessageDate);
                    } else {
                        if (DateUtils.wasDateYesterday(lastMessageDate, Calendar.getInstance().getTime())){
                            timeArgument = "Yesterday";
                        }else {
                            timeArgument = new SimpleDateFormat("M/d/yy").format(lastMessageDate);
                        }
                    }
                }

                for (Handle h : groupChat.getParticipants()){
                    participantDummyList.add(h.getHandleID());
                }

                participantDummyList.remove(participantDummyList.size() - 1);

                if(groupChat.getDisplayName() == null || groupChat.getDisplayName().equals("")){
                    firstArg = StringUtils.join(participantDummyList, ", ", 2) + " & " + groupChat.getParticipants().get(groupChat.getParticipants().size() - 1).getHandleID();
                }else {
                    firstArg = groupChat.getDisplayName();
                }

                if (attachments.isEmpty()){
                    result = executor.runScript(ActionType.SEND_GROUP_MESSAGE, new String[]{ firstArg, timeArgument, lastMessageText, "", decryptedMessage});
                }else if (attachments.size() == 1){
                    result = executor.runScript(ActionType.SEND_GROUP_MESSAGE, new String[] { firstArg, timeArgument, lastMessageText, attachments.get(0).getAbsolutePath(), decryptedMessage });
                }else {
                    for (File file : attachments){
                        executor.runScript(ActionType.SEND_GROUP_MESSAGE, new String[] { firstArg, timeArgument, lastMessageText, file.getAbsolutePath(), "" });
                    }
                    result = executor.runScript(ActionType.SEND_GROUP_MESSAGE, new String[] { firstArg, timeArgument, lastMessageText, "", decryptedMessage });
                }
            }

            return parseResult(result);
        }catch(Exception ex){
            ServerLogger.error(TAG, "An error occurred while relaying a message.", ex);
            return null;
        }
    }

    public List<Integer> performIncomingAction(JSONAction jsonAction){
        AppleScriptExecutor executor = getDeviceManager().getMessageServer().getScriptExecutor();
        Object result = executor.runScript(ActionType.fromCode(jsonAction.getActionType()), jsonAction.getArgs());

        return parseResult(result);
    }

    public void sendOutgoingAction(JSONAction action){
        sendOutgoingMessage(weMessage.JSON_ACTION, action, JSONAction.class);
    }

    public void killDevice(DisconnectReason reason){
        try {
            isRunning.set(false);
            sendOutgoingMessage(weMessage.JSON_CONNECTION_TERMINATED, reason.getCode(), Integer.class);

            getInputStream().close();
            getOutputStream().close();
            getSocket().close();

            interrupt();
        }catch(Exception ex){
            ServerLogger.error(TAG, "An error occurred while disconnecting device with IP Address: " + getAddress(), ex);
            interrupt();
        }
    }

    public void killDeviceByClientMessage(){
        try {
            isRunning.set(false);

            getInputStream().close();
            getOutputStream().close();
            getSocket().close();

            interrupt();
        }catch(Exception ex){
            ServerLogger.error(TAG, "An error occurred while disconnecting device with IP Address: " + getAddress(), ex);
            interrupt();
        }
    }

    public void run(){
        try {
            isRunning.set(true);

            synchronized (outputStreamLock) {
                outputStream = new ObjectOutputStream(getSocket().getOutputStream());
            }

            synchronized (inputStreamLock) {
                inputStream = new ObjectInputStream(getSocket().getInputStream());
            }

            while(!hasTriedVerifying.get() && isRunning.get()){
                try {
                    String keys = AESCrypto.keysToString(AESCrypto.generateKeys());
                    String secret = getDeviceManager().getMessageServer().getConfiguration().getConfigJSON().getConfig().getAccountInfo().getSecret();

                    JSONEncryptedText encryptedText = new JSONEncryptedText(
                            AESCrypto.encryptString(secret, keys),
                            keys
                    );
                    sendOutgoingMessage(weMessage.JSON_VERIFY_PASSWORD_SECRET, encryptedText, JSONEncryptedText.class);
                }catch(Exception ex){
                    ServerLogger.error(TAG, "An error occurred while encrypting the secret key", ex);
                    killDevice(DisconnectReason.ERROR);
                    return;
                }

                try {
                    ServerConfiguration configuration = getDeviceManager().getMessageServer().getConfiguration();
                    InitConnect initConnect = (InitConnect) getIncomingMessage(weMessage.JSON_INIT_CONNECT, getInputStream().readObject()).getIncoming(InitConnect.class, new ByteArrayAdapter(new ServerBase64Wrapper()));
                    String email = AESCrypto.decryptString(initConnect.getEmail().getEncryptedText(), initConnect.getEmail().getKey());
                    String password = AESCrypto.decryptString(initConnect.getPassword().getEncryptedText(), initConnect.getPassword().getKey());

                    if (initConnect.getBuildVersion() != configuration.getBuildVersion()){
                        ServerLogger.log(ServerLogger.Level.INFO, TAG, "Device with IP Address: " + getAddress() + " could not join because it's version does not match the server's.");
                        ServerLogger.log(ServerLogger.Level.INFO, TAG, "Device Version: " + initConnect.getBuildVersion() + "   Server Version: " + configuration.getBuildVersion());
                        killDevice(DisconnectReason.INCORRECT_VERSION);
                        return;
                    }

                    if (!email.equals(configuration.getConfigJSON().getConfig().getAccountInfo().getEmail())){
                        ServerLogger.log(ServerLogger.Level.INFO, TAG, "Device with IP Address: " + getAddress() + " could not join because the wrong email address was provided.");
                        hasTriedVerifying.set(true);
                        killDevice(DisconnectReason.INVALID_LOGIN);
                        return;
                    }

                    if (!password.equals(configuration.getConfigJSON().getConfig().getAccountInfo().getPassword())) {
                        ServerLogger.log(ServerLogger.Level.INFO, TAG, "Device with IP Address: " + getAddress() + " could not join because the wrong password was provided.");
                        hasTriedVerifying.set(true);
                        killDevice(DisconnectReason.INVALID_LOGIN);
                        return;
                    }

                    hasTriedVerifying.set(true);

                    boolean result = getDeviceManager().getDeviceByAddress(getAddress()) != null;

                    if (result) {
                        ServerLogger.error(TAG, "Device with IP Address: " + getAddress() + " could not join because it already connected!", new Exception());
                        killDevice(DisconnectReason.ALREADY_CONNECTED);
                        return;
                    }

                    DeviceType deviceType = DeviceType.fromString(initConnect.getDeviceType());

                    if (deviceType == DeviceType.UNSUPPORTED) {
                        ServerLogger.log(ServerLogger.Level.ERROR, TAG, "Disconnecting device with IP Address: " + getAddress() + " due to an unsupported type format.");
                        killDevice(DisconnectReason.ERROR);
                        return;
                    }

                    synchronized (deviceIdLock){
                        this.deviceId = initConnect.getDeviceId();
                    }

                    synchronized (deviceTypeLock) {
                        this.deviceType = deviceType;
                    }

                    getDeviceManager().addDevice(this);
                }catch(Exception ex){
                    if (isRunning.get()) {
                        ServerLogger.error(TAG, "Device with IP Address: " + getAddress() + " could not join because an error occurred.", ex);
                        killDevice(DisconnectReason.ERROR);
                        return;
                    }
                }
            }

            ServerLogger.log(ServerLogger.Level.INFO, TAG, "A device with an IP of " + getAddress() + " connected.");
            ServerLogger.emptyLine();

            while (isRunning.get()){
                try {
                    String input = (String) getInputStream().readObject();
                    EventManager eventManager = getDeviceManager().getMessageServer().getEventManager();

                    if (input.startsWith(weMessage.JSON_CONNECTION_TERMINATED)){
                        getDeviceManager().removeDevice(this, DisconnectReason.CLIENT_DISCONNECTED, null);
                        return;
                    }
                    if (input.startsWith(weMessage.JSON_NEW_MESSAGE)){
                        ClientMessage clientMessage = getIncomingMessage(weMessage.JSON_NEW_MESSAGE, input);
                        JSONMessage jsonMessage = (JSONMessage) clientMessage.getIncoming(JSONMessage.class, new ByteArrayAdapter(new ServerBase64Wrapper()));
                        List<Integer> returnedResult = relayIncomingMessage(jsonMessage);

                        sendOutgoingMessage(weMessage.JSON_RETURN_RESULT, new JSONResult(clientMessage.getMessageUuid(), returnedResult), JSONResult.class);
                        eventManager.callEvent(new ClientMessageReceivedEvent(eventManager, getDeviceManager(), this, clientMessage, null));

                    }else if (input.startsWith(weMessage.JSON_ACTION)){
                        ClientMessage clientMessage = getIncomingMessage(weMessage.JSON_ACTION, input);
                        JSONAction jsonAction = (JSONAction) clientMessage.getIncoming(JSONAction.class, new ByteArrayAdapter(new ServerBase64Wrapper()));
                        List<Integer> returnedResult =  performIncomingAction(jsonAction);
                        JSONResult jsonResult = new JSONResult(clientMessage.getMessageUuid(), returnedResult);
                        boolean wasRight = jsonResult.getResult().get(0).equals(ReturnType.ACTION_PERFORMED.getCode());

                        sendOutgoingMessage(weMessage.JSON_RETURN_RESULT, jsonResult, JSONResult.class);
                        eventManager.callEvent(new ClientMessageReceivedEvent(eventManager, getDeviceManager(), this, clientMessage, wasRight));
                    }
                }catch(EOFException ex){
                    getDeviceManager().removeDevice(this, DisconnectReason.CLIENT_DISCONNECTED, null);
                }catch (Exception ex) {
                    if (isRunning.get()) {
                        ServerLogger.error(TAG, "An error occurred while fetching a message from Device: " + getAddress(), ex);
                        getDeviceManager().removeDevice(this, DisconnectReason.ERROR, "Removing device in order to prevent error spam.");
                    }
                }
            }
        }catch(IOException ex) {
            if (isRunning.get()) {
                ServerLogger.error(TAG, "An error occurred while establishing a connection with Device " + getAddress(), ex);
                killDevice(DisconnectReason.ERROR);
            }
        }
    }

    private ArrayList<Integer> parseResult(Object result){
        ArrayList<Integer> intResults = new ArrayList<>();
        if (result instanceof List) {
            for (ReturnType returnType : (List<ReturnType>) result) {
                intResults.add(returnType.getCode());
            }
        } else if (result instanceof ReturnType) {
            intResults.add(((ReturnType) result).getCode());
        } else {
            throw new ClassCastException("The result returned from running the script is not a valid return type");
        }
        return intResults;
    }
}