package scott.wemessage.app.ui;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.flipboard.bottomsheet.BottomSheetLayout;
import com.stfalcon.chatkit.commons.ImageLoader;
import com.stfalcon.chatkit.commons.models.IMessage;
import com.stfalcon.chatkit.messages.MessageHolders;
import com.stfalcon.chatkit.messages.MessageInput;
import com.stfalcon.chatkit.messages.MessagesList;
import com.stfalcon.chatkit.messages.MessagesListAdapter;
import com.stfalcon.chatkit.utils.DateFormatter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import scott.wemessage.R;
import scott.wemessage.app.AppLogger;
import scott.wemessage.app.connection.ConnectionService;
import scott.wemessage.app.connection.ConnectionServiceConnection;
import scott.wemessage.app.messages.MessageCallbacks;
import scott.wemessage.app.messages.MessageDatabase;
import scott.wemessage.app.messages.MessageManager;
import scott.wemessage.app.messages.firebase.NotificationCallbacks;
import scott.wemessage.app.messages.objects.ActionMessage;
import scott.wemessage.app.messages.objects.Attachment;
import scott.wemessage.app.messages.objects.Contact;
import scott.wemessage.app.messages.objects.Message;
import scott.wemessage.app.messages.objects.MessageBase;
import scott.wemessage.app.messages.objects.chats.Chat;
import scott.wemessage.app.messages.objects.chats.GroupChat;
import scott.wemessage.app.messages.objects.chats.PeerChat;
import scott.wemessage.app.ui.activities.ChatListActivity;
import scott.wemessage.app.ui.activities.ChatViewActivity;
import scott.wemessage.app.ui.activities.ContactViewActivity;
import scott.wemessage.app.ui.activities.LaunchActivity;
import scott.wemessage.app.ui.activities.MessageImageActivity;
import scott.wemessage.app.ui.activities.MessageVideoActivity;
import scott.wemessage.app.ui.view.chat.ChatTitleView;
import scott.wemessage.app.ui.view.dialog.DialogDisplayer;
import scott.wemessage.app.ui.view.messages.ActionMessageView;
import scott.wemessage.app.ui.view.messages.ActionMessageViewHolder;
import scott.wemessage.app.ui.view.messages.IncomingMessageViewHolder;
import scott.wemessage.app.ui.view.messages.MessageView;
import scott.wemessage.app.ui.view.messages.MessageViewHolder;
import scott.wemessage.app.ui.view.messages.OutgoingMessageViewHolder;
import scott.wemessage.app.utils.IOUtils;
import scott.wemessage.app.utils.FileLocationContainer;
import scott.wemessage.app.utils.media.AudioAttachmentMediaPlayer;
import scott.wemessage.app.weMessage;
import scott.wemessage.commons.json.action.JSONAction;
import scott.wemessage.commons.json.message.JSONMessage;
import scott.wemessage.commons.types.ReturnType;
import scott.wemessage.commons.utils.DateUtils;
import scott.wemessage.commons.utils.FileUtils;
import scott.wemessage.commons.utils.StringUtils;

public class ConversationFragment extends MessagingFragment implements MessageCallbacks, NotificationCallbacks,
        AudioAttachmentMediaPlayer.AttachmentAudioCallbacks, AttachmentPopupFragment.AttachmentInputListener {

    private final String TAG = "ConversationFragment";
    private final Object chatLock = new Object();
    private final int MESSAGE_QUEUE_AMOUNT = 50;
    private final int ERROR_SNACKBAR_DURATION = 5;
    private final byte CONTENT_TYPE_ACTION = 22;

    private String callbackUuid;
    private boolean isBoundToConnectionService = false;
    private boolean isPopupFragmentOpen = false;
    private boolean isSelectionMode = false;

    private String cameraAttachmentInput;
    private String voiceMessageInput;
    private List<String> attachmentsInput = new ArrayList<>();

    private Chat chat;
    private ChatTitleView chatTitleView;
    private MessageInput messageInput;
    private MessagesList messageList;
    private MessagesListAdapter<IMessage> messageListAdapter;

    private View bottomDivider;
    private BottomSheetLayout conversationBottomSheet;
    private FrameLayout galleryFragmentContainer;
    private RelativeLayout messageSelectionModeBar;

    private AudioAttachmentMediaPlayer audioAttachmentMediaPlayer;
    private ConnectionServiceConnection serviceConnection = new ConnectionServiceConnection();

    private ConcurrentHashMap<String, MessageView> selectedMessages = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Message> messageMapIntegrity = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, ActionMessage> actionMessageMapIntegrity = new ConcurrentHashMap<>();

    private BroadcastReceiver messageListBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(weMessage.BROADCAST_CONNECTION_SERVICE_STOPPED)){
                unbindService();
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_server_closed_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_ERROR)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_unknown_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_FORCED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_force_disconnect_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_CLIENT_DISCONNECTED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_client_disconnect_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_NEW_MESSAGE_ERROR)){
                showErroredSnackbar(getString(R.string.new_message_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_SEND_MESSAGE_ERROR)){
                showErroredSnackbar(getString(R.string.send_message_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_MESSAGE_UPDATE_ERROR)) {
                showErroredSnackbar(getString(R.string.message_update_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_ACTION_PERFORM_ERROR)){
                if (intent.getExtras() != null){
                    showErroredSnackbar(intent.getStringExtra(weMessage.BUNDLE_ACTION_PERFORM_ALTERNATE_ERROR_MESSAGE));
                }else {
                    showErroredSnackbar(getString(R.string.action_perform_error_default));
                }
            }else if(intent.getAction().equals(weMessage.BROADCAST_RESULT_PROCESS_ERROR)){
                showErroredSnackbar(getString(R.string.result_process_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_LOAD_ATTACHMENT_ERROR)){
                showErroredSnackbar(getString(R.string.load_attachment_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_PLAY_AUDIO_ATTACHMENT_ERROR)){
                showErroredSnackbar(getString(R.string.play_audio_attachment_error));
            }else if (intent.getAction().equals(weMessage.BROADCAST_IMAGE_FULLSCREEN_ACTIVITY_START)){
                launchFullScreenImageActivity(intent.getStringExtra(weMessage.BUNDLE_FULL_SCREEN_IMAGE_URI));
            }else if (intent.getAction().equals(weMessage.BROADCAST_VIDEO_FULLSCREEN_ACTIVITY_START)){
                launchFullScreenVideoActivity(intent.getStringExtra(weMessage.BUNDLE_FULL_SCREEN_VIDEO_URI));
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        if (isServiceRunning(ConnectionService.class)){
            bindService();
        }

        MessageManager messageManager = weMessage.get().getMessageManager();
        MessageDatabase messageDatabase = weMessage.get().getMessageDatabase();

        if (savedInstanceState == null) {
            try {
                Intent startingIntent = getActivity().getIntent();
                Class startingClass = Class.forName(startingIntent.getStringExtra(weMessage.BUNDLE_RETURN_POINT));

                Chat chat = messageDatabase.getChatByUuid(startingIntent.getStringExtra(weMessage.BUNDLE_CONVERSATION_CHAT));

                if (chat == null) {
                    Intent returnIntent = new Intent(weMessage.get(), startingClass);
                    returnIntent.putExtra(weMessage.BUNDLE_CONVERSATION_GO_BACK_REASON, getString(R.string.conversation_load_failure));

                    startActivity(returnIntent);
                    getActivity().finish();
                } else {
                    setChat(chat);
                }
            } catch (Exception ex) {
                AppLogger.error(TAG, "Could not load ConversationFragment because an error occurred", ex);
                goToChatList(getString(R.string.conversation_load_failure));
            }
        }else {
            setChat(messageDatabase.getChatByUuid(savedInstanceState.getString(weMessage.BUNDLE_CONVERSATION_CHAT)));
            attachmentsInput = savedInstanceState.getStringArrayList(weMessage.BUNDLE_SELECTED_GALLERY_STORE);
            cameraAttachmentInput = savedInstanceState.getString(weMessage.BUNDLE_CAMERA_ATTACHMENT_FILE);
            voiceMessageInput = savedInstanceState.getString(weMessage.BUNDLE_VOICE_MESSAGE_INPUT_FILE);
        }

        IntentFilter broadcastIntentFilter = new IntentFilter();

        broadcastIntentFilter.addAction(weMessage.BROADCAST_CONNECTION_SERVICE_STOPPED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_FORCED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_CLIENT_DISCONNECTED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_NEW_MESSAGE_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_SEND_MESSAGE_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_MESSAGE_UPDATE_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_ACTION_PERFORM_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_RESULT_PROCESS_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_LOAD_ATTACHMENT_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_PLAY_AUDIO_ATTACHMENT_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_IMAGE_FULLSCREEN_ACTIVITY_START);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_VIDEO_FULLSCREEN_ACTIVITY_START);

        callbackUuid = UUID.randomUUID().toString();

        messageManager.hookCallbacks(callbackUuid, this);
        weMessage.get().setNotificationCallbacks(this);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(messageListBroadcastReceiver, broadcastIntentFilter);

        super.onCreate(savedInstanceState);
    }


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_conversation, container, false);

        Toolbar toolbar = (Toolbar) getActivity().findViewById(R.id.conversationToolbar);
        ImageButton backButton = (ImageButton) toolbar.findViewById(R.id.conversationBackButton);
        ImageButton infoButton = (ImageButton) toolbar.findViewById(R.id.conversationInfoButton);

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToChatList(null);
            }
        });

        infoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getChat() instanceof PeerChat){
                    launchContactView();
                }else if (getChat() instanceof GroupChat){
                    launchChatView();
                }
            }
        });

        toolbar.setTitle(null);
        ((AppCompatActivity)getActivity()).setSupportActionBar(toolbar);

        chatTitleView = (ChatTitleView) toolbar.findViewById(R.id.chatTitleView);
        messageList = (MessagesList) view.findViewById(R.id.messagesList);
        messageInput = (MessageInput) view.findViewById(R.id.messageInputView);

        bottomDivider = view.findViewById(R.id.messageInputDivider);
        conversationBottomSheet = (BottomSheetLayout) view.findViewById(R.id.conversationBottomSheetLayout);
        galleryFragmentContainer = (FrameLayout) view.findViewById(R.id.galleryFragmentContainer);
        messageSelectionModeBar = (RelativeLayout) view.findViewById(R.id.messageSelectionModeBar);

        ImageLoader imageLoader;
        final MessageManager messageManager = weMessage.get().getMessageManager();
        final String meUuid = weMessage.get().getMessageDatabase().getContactByHandle(weMessage.get().getMessageDatabase().getHandleByAccount(weMessage.get().getCurrentAccount())).getUuid().toString();

        MessageHolders messageHolders = new MessageHolders()
                .setIncomingTextConfig(IncomingMessageViewHolder.class, R.layout.incoming_message)
                .setOutcomingTextConfig(OutgoingMessageViewHolder.class, R.layout.outgoing_message)
                .registerContentType(CONTENT_TYPE_ACTION, ActionMessageViewHolder.class, R.layout.message_action, R.layout.message_action, new MessageHolders.ContentChecker() {
                    @Override
                    public boolean hasContentFor(IMessage message, byte type) {
                        switch (type){
                            case CONTENT_TYPE_ACTION:
                                return (message instanceof ActionMessageView);
                        }
                        return false;
                    }
                });

        if (getChat() instanceof PeerChat){
            imageLoader = null;
        } else {
            imageLoader = new ImageLoader() {
                @Override
                public void loadImage(ImageView imageView, String url) {
                    Glide.with(ConversationFragment.this).load(url).into(imageView);
                }
            };
        }

        final MessagesListAdapter<IMessage> messageListAdapter = new MessagesListAdapter<>(meUuid, messageHolders, imageLoader);

        messageListAdapter.setDateHeadersFormatter(new DateFormatter.Formatter() {
            @Override
            public String format(Date date) {
                if (DateFormatter.isToday(date)){
                    return getString(R.string.word_today);
                }else if (DateFormatter.isYesterday(date)){
                    return getString(R.string.word_yesterday);
                }else {
                    if (DateFormatter.isCurrentYear(date)){
                        return DateFormatter.format(date, "MMMM d");
                    }else {
                        return DateFormatter.format(date, "MMMM d yyyy");
                    }
                }
            }
        });

        messageListAdapter.setLoadMoreListener(new MessagesListAdapter.OnLoadMoreListener() {
            @Override
            public void onLoadMore(int page, int totalItemsCount) {
                weMessage.get().getMessageManager().queueMessages(getChat(), totalItemsCount, MESSAGE_QUEUE_AMOUNT, true);
            }
        });

        messageListAdapter.setOnMessageViewClickListener(new MessagesListAdapter.OnMessageViewClickListener<IMessage>() {
            @Override
            public void onMessageViewClick(View view, IMessage message) {
                if (message instanceof MessageView){
                    if (isSelectionMode){
                        for (int childCount = messageList.getChildCount(), i = 0; i < childCount; ++i) {
                            RecyclerView.ViewHolder holder = messageList.getChildViewHolder(messageList.getChildAt(i));

                            if (holder instanceof MessageViewHolder){
                                MessageViewHolder messageHolder = (MessageViewHolder) holder;

                                if (((MessageViewHolder) holder).getMessageId().equals(message.getId())) {
                                    if (!selectedMessages.containsKey(message.getId())) {
                                        selectedMessages.put(message.getId(), (MessageView) message);
                                        messageHolder.setSelected(true);
                                    } else {
                                        selectedMessages.remove(message.getId());
                                        messageHolder.setSelected(false);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        messageListAdapter.setOnMessageViewLongClickListener(new MessagesListAdapter.OnMessageViewLongClickListener<IMessage>() {
            @Override
            public void onMessageViewLongClick(View view, final IMessage message) {
                if (message instanceof MessageView) {
                    if (!isSelectionMode) {
                        showMessageOptionsSheetView((MessageView) message);
                    }
                }
            }
        });

        chatTitleView.setChat(getChat());
        messageList.setAdapter(messageListAdapter);
        this.messageListAdapter = messageListAdapter;

        messageInput.setAttachmentsListener(new MessageInput.AttachmentsListener() {
            @Override
            public void onAddAttachments() {
                clearEditText((EditText) messageInput.findViewById(R.id.messageInput), true);

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isPopupFragmentOpen) {
                            launchAttachmentPopupFragment();
                        }else {
                            closeAttachmentPopupFragment();
                        }
                    }
                }, 200);
            }
        });

        messageInput.setInputListener(new MessageInput.InputListener() {
            @Override
            public boolean onSubmit(CharSequence input) {
                return sendMessage(input);
            }
        });

        messageInput.findViewById(R.id.messageInput).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isPopupFragmentOpen){
                    closeAttachmentPopupFragment();
                }
                return false;
            }
        });

        messageSelectionModeBar.findViewById(R.id.messageSelectCopyIcon).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (selectedMessages.size() == 0){
                    showErroredSnackbar(getString(R.string.selection_mode_no_messages));
                    return;
                }

                copyMessages(new ArrayList<>(selectedMessages.values()));
                toggleSelectionMode(false);
            }
        });

        messageSelectionModeBar.findViewById(R.id.messageSelectDeleteIcon).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (selectedMessages.size() == 0){
                    showErroredSnackbar(getString(R.string.selection_mode_no_messages));
                    return;
                }

                for (MessageView messageView : selectedMessages.values()){
                    weMessage.get().getMessageManager().removeMessage(messageView.getMessage(), true);
                }
                toggleSelectionMode(false);
            }
        });

        messageSelectionModeBar.findViewById(R.id.messageSelectCancelIcon).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleSelectionMode(false);
            }
        });

        messageManager.queueMessages(getChat(), 0, MESSAGE_QUEUE_AMOUNT, true);
        messageManager.setHasUnreadMessages(getChat(), false, true);

        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(weMessage.BUNDLE_GALLERY_FRAGMENT_OPEN)) {
                launchAttachmentPopupFragment();
            }
        }

        toggleIsInChat(chat.isInChat());

        NotificationManager notificationManager = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            List<Integer> toCancel = new ArrayList<>();

            for (StatusBarNotification notification : notificationManager.getActiveNotifications()){
                if (notification.getTag().equals(weMessage.NOTIFICATION_TAG + chat.getUuid().toString()) || notification.getTag().equals(weMessage.NOTIFICATION_TAG)){
                    toCancel.add(notification.getId());
                }
            }

            for (Integer i : toCancel){
                notificationManager.cancel(i);
            }
        }else {
            notificationManager.cancelAll();
        }

        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == weMessage.REQUEST_CODE_CAMERA){
            if (getAttachmentPopupFragment() == null){
                launchAttachmentPopupFragmentWithCameraIntent(resultCode, data);
            }else {
                getAttachmentPopupFragment().onCameraResult(resultCode, data);
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(weMessage.BUNDLE_GALLERY_FRAGMENT_OPEN, isPopupFragmentOpen);
        outState.putString(weMessage.BUNDLE_CONVERSATION_CHAT, getChat().getUuid().toString());

        if (getAttachmentPopupFragment() != null) {
            outState.putString(weMessage.BUNDLE_CAMERA_ATTACHMENT_FILE, getAttachmentPopupFragment().getCameraAttachmentFile());
            outState.putString(weMessage.BUNDLE_VOICE_MESSAGE_INPUT_FILE, getAttachmentPopupFragment().getAudioFile());
            outState.putStringArrayList(weMessage.BUNDLE_SELECTED_GALLERY_STORE, new ArrayList<>(getAttachmentPopupFragment().getSelectedAttachments()));
        }else {
            outState.putString(weMessage.BUNDLE_CAMERA_ATTACHMENT_FILE, cameraAttachmentInput);
            outState.putString(weMessage.BUNDLE_VOICE_MESSAGE_INPUT_FILE, voiceMessageInput);
            outState.putStringArrayList(weMessage.BUNDLE_SELECTED_GALLERY_STORE, new ArrayList<>(attachmentsInput));
        }

        if (isPopupFragmentOpen){
            closeAttachmentPopupFragment();
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onPause() {
        pauseAudio(getAudioAttachmentMediaPlayer().getAttachment());

        super.onPause();
    }

    @Override
    public void onResume() {
        if (!isServiceRunning(ConnectionService.class)){
            goToLauncher();
        }

        super.onResume();
    }

    @Override
    public void onDestroy() {
        MessageManager messageManager = weMessage.get().getMessageManager();

        messageMapIntegrity.clear();
        actionMessageMapIntegrity.clear();
        messageListAdapter.clear();
        messageManager.unhookCallbacks(callbackUuid);
        weMessage.get().setNotificationCallbacks(null);
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(messageListBroadcastReceiver);

        if (isBoundToConnectionService){
            unbindService();
        }

        stopAudio();
        super.onDestroy();
    }

    @Override
    public void onContactCreate(Contact contact) {

    }

    @Override
    public void onContactUpdate(Contact oldData, Contact newData) {

    }

    @Override
    public void onContactListRefresh(List<Contact> contacts) {

    }

    @Override
    public void onChatAdd(Chat chat) {

    }

    @Override
    public void onChatUpdate(Chat oldData, final Chat newData) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(newData)){
                    setChat(newData);
                    toggleIsInChat(chat.isInChat());
                }
            }
        });
    }

    @Override
    public void onUnreadMessagesUpdate(final Chat chat, boolean hasUnreadMessages) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(chat)){
                    setChat(chat);
                }
            }
        });
    }

    @Override
    public void onChatRename(final Chat chat, String displayName) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(chat)){
                    setChat(chat);
                    chatTitleView.setChat(chat);
                }
            }
        });
    }

    @Override
    public void onParticipantAdd(final Chat chat, Contact contact) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(isChatThis(chat)){
                    setChat(chat);
                    chatTitleView.setChat(chat);
                }
            }
        });
    }

    @Override
    public void onParticipantRemove(final Chat chat, Contact contact) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(chat)){
                    setChat(chat);
                    chatTitleView.setChat(chat);
                }
            }
        });
    }

    @Override
    public void onLeaveGroup(final Chat chat) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(chat)){
                    setChat(chat);
                    toggleIsInChat(chat.isInChat());
                }
            }
        });
    }

    @Override
    public void onChatDelete(final Chat chat) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(chat)){
                    goToChatList(getString(R.string.chat_delete_message_go_back));
                }
            }
        });
    }

    @Override
    public void onChatListRefresh(List<Chat> chats) {

    }

    @Override
    public void onMessageAdd(final Message message) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(message.getChat())){
                    if (isMessageBlocked(message)) return;

                    MessageView messageView = new MessageView(message);
                    messageListAdapter.addToStart(messageView, true);
                    messageMapIntegrity.put(message.getUuid().toString(), message);
                    weMessage.get().getMessageManager().setHasUnreadMessages(getChat(), false, true);
                }
            }
        });
    }

    @Override
    public void onMessageUpdate(final Message oldData, final Message newData) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(newData.getChat())){
                    if (isMessageBlocked(newData)) return;

                    messageListAdapter.update(oldData.getUuid().toString(), new MessageView(newData));
                    messageMapIntegrity.put(newData.getUuid().toString(), newData);
                }
            }
        });
    }

    @Override
    public void onMessageDelete(final Message message) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(isChatThis(message.getChat())){
                    if (isMessageBlocked(message)) return;

                    messageListAdapter.deleteById(message.getUuid().toString());
                    messageMapIntegrity.remove(message.getUuid().toString());
                }
            }
        });
    }

    @Override
    public void onMessagesQueueFinish(final List<MessageBase> messages) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (messages.size() > 0) {
                    List<IMessage> messageViews = new ArrayList<>();

                    for (MessageBase messageBase : messages){
                        if (messageBase instanceof ActionMessage){
                            ActionMessage actionMessage = (ActionMessage) messageBase;

                            if (isChatThis(actionMessage.getChat())) {
                                if (!actionMessageMapIntegrity.containsKey(actionMessage.getUuid().toString())) {
                                    ActionMessageView messageView = new ActionMessageView(actionMessage);

                                    messageViews.add(messageView);
                                    actionMessageMapIntegrity.put(actionMessage.getUuid().toString(), actionMessage);
                                }
                            }
                        } else if (messageBase instanceof Message){
                            Message message = (Message) messageBase;

                            if (isChatThis(message.getChat())) {
                                if (isMessageBlocked(message)) return;

                                if (!messageMapIntegrity.containsKey(message.getUuid().toString())) {
                                    MessageView messageView = new MessageView(message);

                                    messageViews.add(messageView);
                                    messageMapIntegrity.put(message.getUuid().toString(), message);
                                }
                            }
                        }
                    }

                    if (messageViews.size() > 0) {
                        messageListAdapter.addToEnd(messageViews, false);
                    }
                }
            }
        });
    }

    @Override
    public void onMessagesRefresh() {
        messageListAdapter.clear();
        messageMapIntegrity.clear();
        actionMessageMapIntegrity.clear();
        weMessage.get().getMessageManager().queueMessages(getChat(), 0, MESSAGE_QUEUE_AMOUNT, true);
    }

    @Override
    public void onActionMessageAdd(final ActionMessage message) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isChatThis(message.getChat())){
                    ActionMessageView messageView = new ActionMessageView(message);
                    messageListAdapter.addToStart(messageView, true);
                    actionMessageMapIntegrity.put(message.getUuid().toString(), message);
                }
            }
        });
    }

    @Override
    public void onMessageSendFailure(JSONMessage jsonMessage, final ReturnType returnType) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (getView() != null) {
                    switch (returnType) {
                        case INVALID_NUMBER:
                            showErroredSnackbar(getString(R.string.message_delivery_failure_invalid_number));
                            break;
                        case NUMBER_NOT_IMESSAGE:
                            showErroredSnackbar(getString(R.string.message_delivery_failure_imessage));
                            break;
                        case GROUP_CHAT_NOT_FOUND:
                            showErroredSnackbar(getString(R.string.message_delivery_failure_group_chat));
                            break;
                        case SERVICE_NOT_AVAILABLE:
                            showErroredSnackbar(getString(R.string.message_delivery_failure_service));
                            break;
                        case ASSISTIVE_ACCESS_DISABLED:
                            showErroredSnackbar(getString(R.string.message_delivery_failure_assistive));
                            break;
                        case UI_ERROR:
                            showErroredSnackbar(getString(R.string.message_delivery_failure_ui_error));
                            break;
                    }
                }
            }
        });
    }

    @Override
    public void onActionPerformFailure(final JSONAction jsonAction, final ReturnType returnType) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showActionFailureSnackbar(jsonAction, returnType);
            }
        });
    }

    @Override
    public void onPlaybackStart(Attachment a) {
        for (int childCount = messageList.getChildCount(), i = 0; i < childCount; ++i) {
            RecyclerView.ViewHolder holder = messageList.getChildViewHolder(messageList.getChildAt(i));

            if (holder instanceof MessageViewHolder){
                MessageViewHolder messageHolder = (MessageViewHolder) holder;

                messageHolder.notifyAudioPlaybackStart(a);
            }
        }
    }

    @Override
    public void onPlaybackStop(String attachmentUuid) {
        for (int childCount = messageList.getChildCount(), i = 0; i < childCount; ++i) {
            RecyclerView.ViewHolder holder = messageList.getChildViewHolder(messageList.getChildAt(i));

            if (holder instanceof MessageViewHolder){
                MessageViewHolder messageHolder = (MessageViewHolder) holder;

                messageHolder.notifyAudioPlaybackStop(attachmentUuid);
            }
            audioAttachmentMediaPlayer = null;
        }
    }

    @Override
    public void setAttachmentsInput(List<String> attachments, String cameraAttachmentFile, String audioFile) {
        if (isResumed()){
            if (attachments.size() > 0 && attachments.size() != attachmentsInput.size()){
                Toast.makeText(getActivity(), getString(R.string.attachments_added_toast, attachments.size()), Toast.LENGTH_SHORT).show();
            }
            attachmentsInput = new ArrayList<>(attachments);

            if (audioFile != null && !audioFile.equals(voiceMessageInput)){
                Toast.makeText(getActivity(), getString(R.string.audio_recording_added), Toast.LENGTH_SHORT).show();
            }

            if (cameraAttachmentFile != null && !cameraAttachmentFile.equals(cameraAttachmentInput)){
                Toast.makeText(getActivity(), getString(R.string.camera_attachment_added), Toast.LENGTH_SHORT).show();
            }
            cameraAttachmentInput = cameraAttachmentFile;
            voiceMessageInput = audioFile;
        }
    }

    @Override
    public boolean onNotification(String macGuid) {
        if (getChat().getMacGuid().equals(macGuid)){
            return false;
        }
        return true;
    }

    public synchronized AudioAttachmentMediaPlayer getAudioAttachmentMediaPlayer(){
        if (audioAttachmentMediaPlayer == null){
            audioAttachmentMediaPlayer = new AudioAttachmentMediaPlayer();
            audioAttachmentMediaPlayer.setCallback(this);
        }
        return audioAttachmentMediaPlayer;
    }

    public ConcurrentHashMap<String, MessageView> getSelectedMessages(){
        return selectedMessages;
    }

    public synchronized boolean playAudio(Attachment a){
        try {
            if (getAudioAttachmentMediaPlayer().hasAudio()) {
                getAudioAttachmentMediaPlayer().stopAudioPlayback();
            }
            getAudioAttachmentMediaPlayer().setAttachment(a);
            getAudioAttachmentMediaPlayer().startAudioPlayback(IOUtils.getUriFromFile(a.getFileLocation().getFile()));
            return true;
        }catch(Exception ex){
            AppLogger.error("An error occurred while trying to play Audio Attachment: " + a.getUuid().toString(), ex);
            showErroredSnackbar(getString(R.string.play_audio_attachment_error));
            getAudioAttachmentMediaPlayer().forceRelease();
            return false;
        }
    }

    public synchronized boolean resumeAudio(Attachment a){
        if (getAudioAttachmentMediaPlayer().hasAudio() && getAudioAttachmentMediaPlayer().getAttachment().getUuid().toString().equals(a.getUuid().toString())){
            getAudioAttachmentMediaPlayer().resumeAudioPlayback();
            return true;
        }
        return false;
    }

    public synchronized boolean pauseAudio(Attachment a){
        if (getAudioAttachmentMediaPlayer().hasAudio() && getAudioAttachmentMediaPlayer().getAttachment().getUuid().toString().equals(a.getUuid().toString())){
            getAudioAttachmentMediaPlayer().pauseAudioPlayback();
            return true;
        }
        return false;
    }

    public synchronized boolean stopAudio(){
        if (getAudioAttachmentMediaPlayer().hasAudio()){
            getAudioAttachmentMediaPlayer().stopAudioPlayback();
            return true;
        }
        return false;
    }

    public synchronized boolean isInSelectionMode(){
        return isSelectionMode;
    }

    private AttachmentPopupFragment getAttachmentPopupFragment(){
        if (getChildFragmentManager().findFragmentById(R.id.galleryFragmentContainer) == null) return null;

        return (AttachmentPopupFragment) getChildFragmentManager().findFragmentById(R.id.galleryFragmentContainer);
    }

    private Chat getChat(){
        synchronized (chatLock){
            return chat;
        }
    }

    private void setChat(Chat chat){
        synchronized (chatLock){
            this.chat = chat;
        }
    }

    private boolean sendMessage(CharSequence input){
        if (StringUtils.isEmpty(input.toString().trim())) return false;

        List<Attachment> attachments = new ArrayList<>();
        int totalSize = 0;

        if (isPopupFragmentOpen) {
            if (getAttachmentPopupFragment() != null) {
                for (String s : getAttachmentPopupFragment().getSelectedAttachments()) {

                    try {
                        String attachmentNamePrefix = new SimpleDateFormat("HH-mm-ss_MM-dd-yyyy", Locale.US).format(Calendar.getInstance().getTime());
                        String transferName = Uri.parse(s).getLastPathSegment();
                        File copiedFile = new File(weMessage.get().getAttachmentFolder(), attachmentNamePrefix + "-" + transferName);

                        copiedFile.createNewFile();
                        FileUtils.copy(new File(s), copiedFile);

                        int totalBytes = Math.round(copiedFile.length());
                        Attachment a = new Attachment(
                                UUID.randomUUID(),
                                null,
                                transferName,
                                new FileLocationContainer(copiedFile),
                                MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(s)),
                                totalBytes
                        );
                        attachments.add(a);
                        totalSize += totalBytes;

                    }catch (Exception ex){
                        showErroredSnackbar(getString(R.string.send_attachment_error));
                        AppLogger.error("An error occurred while loading a file into a message.", ex);
                    }
                }
            }
            getAttachmentPopupFragment().clearSelectedAttachments();
            closeAttachmentPopupFragment();
        } else {
            for (String s : attachmentsInput) {
                try {
                    String attachmentNamePrefix = new SimpleDateFormat("HH-mm-ss_MM-dd-yyyy", Locale.US).format(Calendar.getInstance().getTime());
                    String transferName = Uri.parse(s).getLastPathSegment();
                    File copiedFile = new File(weMessage.get().getAttachmentFolder(), attachmentNamePrefix + "-" + transferName);

                    copiedFile.createNewFile();
                    FileUtils.copy(new File(s), copiedFile);

                    int totalBytes = Math.round(copiedFile.length());
                    Attachment a = new Attachment(
                            UUID.randomUUID(),
                            null,
                            transferName,
                            new FileLocationContainer(copiedFile),
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(s)),
                            totalBytes
                    );
                    attachments.add(a);
                    totalSize += totalBytes;

                }catch (Exception ex){
                    showErroredSnackbar(getString(R.string.send_attachment_error));
                    AppLogger.error("An error occurred while loading a file into a message.", ex);
                }
            }
        }

        if (cameraAttachmentInput != null){
            File inputFile = new File(cameraAttachmentInput);

            if (inputFile.exists()) {
                int totalBytes = Math.round(inputFile.length());
                Attachment a = new Attachment(
                        UUID.randomUUID(),
                        null,
                        Uri.parse(cameraAttachmentInput).getLastPathSegment(),
                        new FileLocationContainer(cameraAttachmentInput),
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(cameraAttachmentInput)),
                        totalBytes
                );
                attachments.add(a);
                totalSize += totalBytes;
            }
        }

        if (voiceMessageInput != null){
            File inputFile = new File(voiceMessageInput);
            if (inputFile.exists()) {
                int totalBytes = Math.round(inputFile.length());
                Attachment a = new Attachment(
                        UUID.randomUUID(),
                        null,
                        Uri.parse(voiceMessageInput).getLastPathSegment(),
                        new FileLocationContainer(voiceMessageInput),
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(voiceMessageInput)),
                        totalBytes
                );
                attachments.add(a);
                totalSize += totalBytes;
            }
        }

        if (totalSize > weMessage.MAX_FILE_SIZE){
            DialogDisplayer.generateAlertDialog(getString(R.string.max_file_size_alert_title), getString(R.string.max_file_size_alert_message, FileUtils.getFileSizeString(weMessage.MAX_FILE_SIZE)))
                    .show(getFragmentManager(), "AttachmentMaxFileSizeAlert");
            return false;
        }

        Message message = new Message(
                UUID.randomUUID(),
                null,
                getChat(),
                weMessage.get().getMessageDatabase().getContactByHandle(weMessage.get().getMessageDatabase().getHandleByAccount(weMessage.get().getCurrentAccount())),
                attachments,
                input.toString().trim(),
                DateUtils.convertDateTo2001Time(Calendar.getInstance().getTime()),
                null,
                null,
                false,
                true,
                false,
                false,
                true,
                true
        );
        serviceConnection.getConnectionService().getConnectionHandler().sendOutgoingMessage(message, true);
        attachmentsInput.clear();
        cameraAttachmentInput = null;
        voiceMessageInput = null;
        return true;
    }

    private void showMessageOptionsSheetView(final MessageView message){
        conversationBottomSheet.showWithSheetView(LayoutInflater.from(getActivity()).inflate(R.layout.sheet_conversation_message_options, conversationBottomSheet, false));

        conversationBottomSheet.findViewById(R.id.conversationSheetCopyButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (StringUtils.isEmpty(message.getText()) && message.getMessage().getAttachments().size() > 0){
                    Toast.makeText(getActivity(), getString(R.string.copy_message_attachments), Toast.LENGTH_SHORT).show();
                    return;
                }
                ArrayList<MessageView> messageList = new ArrayList<>();
                messageList.add(message);

                copyMessages(messageList);
                conversationBottomSheet.dismissSheet();
            }
        });

        conversationBottomSheet.findViewById(R.id.conversationSheetDeleteButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                weMessage.get().getMessageManager().removeMessage(message.getMessage(), true);
                conversationBottomSheet.dismissSheet();
            }
        });

        conversationBottomSheet.findViewById(R.id.conversationSheetMoreButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleSelectionMode(true);
                conversationBottomSheet.dismissSheet();
            }
        });
    }

    private void copyMessages(List<MessageView> messages){
        String fullMessage = "";

        Collections.sort(messages, new Comparator<MessageView>() {
            @Override
            public int compare(MessageView o1, MessageView o2) {
                return o1.getCreatedAt().compareTo(o2.getCreatedAt());
            }
        });

        for (MessageView message : messages){
            if (!StringUtils.isEmpty(message.getText())) {
                String createdAt;

                if (DateFormatter.isCurrentYear(message.getCreatedAt())) {
                    createdAt = new SimpleDateFormat("EEEE, MMMM d 'at' h:mm a", Locale.getDefault()).format(message.getCreatedAt());
                } else {
                    createdAt = new SimpleDateFormat("EEEE, MMMM d yyyy 'at' h:mm a", Locale.getDefault()).format(message.getCreatedAt());
                }

                fullMessage += String.format(Locale.getDefault(), "%s: %s (%s)", message.getUser().getName(), message.getText(), createdAt) + "\n";
            }
        }

        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(fullMessage, fullMessage);
        clipboard.setPrimaryClip(clip);

        if (messages.size() == 1) {
            Toast.makeText(getActivity(), getString(R.string.copy_message_success_single), Toast.LENGTH_SHORT).show();
        }else {
            Toast.makeText(getActivity(), getString(R.string.copy_message_success_multiple), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleSelectionMode(boolean value){
        if (isSelectionMode != value) {
            isSelectionMode = value;

            if (chat.isInChat()) {
                if (value) {
                    RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) bottomDivider.getLayoutParams();

                    layoutParams.removeRule(RelativeLayout.ABOVE);
                    layoutParams.addRule(RelativeLayout.ABOVE, R.id.messageSelectionModeBar);

                    bottomDivider.setLayoutParams(layoutParams);
                    messageInput.setVisibility(View.GONE);
                    messageSelectionModeBar.setVisibility(View.VISIBLE);
                } else {
                    RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) bottomDivider.getLayoutParams();

                    layoutParams.removeRule(RelativeLayout.ABOVE);
                    layoutParams.addRule(RelativeLayout.ABOVE, R.id.messageInputView);

                    bottomDivider.setLayoutParams(layoutParams);
                    messageSelectionModeBar.setVisibility(View.GONE);
                    messageInput.setVisibility(View.VISIBLE);
                }
            } else {
                if (value) {
                    RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) messageList.getLayoutParams();

                    layoutParams.removeRule(RelativeLayout.ABOVE);
                    layoutParams.addRule(RelativeLayout.ABOVE, R.id.messageSelectionModeBar);

                    messageList.setLayoutParams(layoutParams);
                    messageSelectionModeBar.setVisibility(View.VISIBLE);
                } else {
                    RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) messageList.getLayoutParams();

                    layoutParams.removeRule(RelativeLayout.ABOVE);

                    messageList.setLayoutParams(layoutParams);
                    messageSelectionModeBar.setVisibility(View.GONE);
                }
            }

            for (int childCount = messageList.getChildCount(), i = 0; i < childCount; ++i) {
                RecyclerView.ViewHolder holder = messageList.getChildViewHolder(messageList.getChildAt(i));

                if (holder instanceof MessageViewHolder){
                    MessageViewHolder messageHolder = (MessageViewHolder) holder;

                    if (!value){
                        messageHolder.setSelected(false);
                    }

                    messageHolder.toggleSelectionMode(value);
                }
            }
        }
    }

    private void toggleIsInChat(boolean value){
        toggleSelectionMode(false);

        if (value){
            if (messageInput.getVisibility() != View.VISIBLE) {
                messageInput.setVisibility(View.VISIBLE);
                bottomDivider.setVisibility(View.VISIBLE);

                RelativeLayout.LayoutParams messageListLayoutParams = (RelativeLayout.LayoutParams) messageList.getLayoutParams();
                RelativeLayout.LayoutParams galleryFragmentLayoutParams = (RelativeLayout.LayoutParams) galleryFragmentContainer.getLayoutParams();

                messageListLayoutParams.addRule(RelativeLayout.ABOVE, R.id.messageInputDivider);
                galleryFragmentLayoutParams.removeRule(RelativeLayout.BELOW);
                galleryFragmentLayoutParams.addRule(RelativeLayout.BELOW, R.id.messageInputView);

                messageList.setLayoutParams(messageListLayoutParams);
                galleryFragmentContainer.setLayoutParams(galleryFragmentLayoutParams);
            }
        }else {
            if (messageInput.getVisibility() != View.GONE) {
                messageInput.setVisibility(View.GONE);
                bottomDivider.setVisibility(View.GONE);

                RelativeLayout.LayoutParams messageListLayoutParams = (RelativeLayout.LayoutParams) messageList.getLayoutParams();
                RelativeLayout.LayoutParams galleryFragmentLayoutParams = (RelativeLayout.LayoutParams) galleryFragmentContainer.getLayoutParams();

                messageListLayoutParams.removeRule(RelativeLayout.ABOVE);
                galleryFragmentLayoutParams.removeRule(RelativeLayout.BELOW);
                galleryFragmentLayoutParams.addRule(RelativeLayout.BELOW, R.id.messagesList);

                messageList.setLayoutParams(messageListLayoutParams);
                galleryFragmentContainer.setLayoutParams(galleryFragmentLayoutParams);
            }
        }
    }

    private void launchContactView(){
        Intent launcherIntent = new Intent(weMessage.get(), ContactViewActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_CONTACT_VIEW_UUID, ((PeerChat) getChat()).getContact().getUuid().toString());
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, getChat().getUuid().toString());

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void launchChatView(){
        Intent launcherIntent = new Intent(weMessage.get(), ChatViewActivity.class);
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, getChat().getUuid().toString());

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void launchFullScreenImageActivity(String imageUri){
        Intent launcherIntent = new Intent(weMessage.get(), MessageImageActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_FULL_SCREEN_IMAGE_URI, imageUri);
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, getChat().getUuid().toString());

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void launchFullScreenVideoActivity(String imageUri){
        Intent launcherIntent = new Intent(weMessage.get(), MessageVideoActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_FULL_SCREEN_VIDEO_URI, imageUri);
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, getChat().getUuid().toString());

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void launchAttachmentPopupFragment(){
        if (!isPopupFragmentOpen) {
            isPopupFragmentOpen = true;

            RelativeLayout.LayoutParams messageInputLayoutParams = (RelativeLayout.LayoutParams) messageInput.getLayoutParams();
            RelativeLayout.LayoutParams fragmentContainerLayoutParams = (RelativeLayout.LayoutParams) galleryFragmentContainer.getLayoutParams();

            messageInputLayoutParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            messageInputLayoutParams.addRule(RelativeLayout.ABOVE, R.id.galleryFragmentContainer);
            fragmentContainerLayoutParams.removeRule(RelativeLayout.BELOW);
            fragmentContainerLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);

            messageInput.setLayoutParams(messageInputLayoutParams);
            galleryFragmentContainer.setLayoutParams(fragmentContainerLayoutParams);
            galleryFragmentContainer.setVisibility(View.VISIBLE);

            AttachmentPopupFragment popupFragment = new AttachmentPopupFragment();
            Bundle popupArgs = new Bundle();

            popupArgs.putString(weMessage.ARG_CAMERA_ATTACHMENT_FILE, cameraAttachmentInput);
            popupArgs.putString(weMessage.ARG_VOICE_RECORDING_FILE, voiceMessageInput);
            popupArgs.putStringArrayList(weMessage.ARG_ATTACHMENT_GALLERY_CACHE, new ArrayList<>(attachmentsInput));
            popupFragment.setArguments(popupArgs);

            getChildFragmentManager().beginTransaction().add(R.id.galleryFragmentContainer, popupFragment).commit();
        }
    }

    private void launchAttachmentPopupFragmentWithCameraIntent(int resultCode, Intent data){
        isPopupFragmentOpen = true;

        RelativeLayout.LayoutParams messageInputLayoutParams = (RelativeLayout.LayoutParams) messageInput.getLayoutParams();
        RelativeLayout.LayoutParams fragmentContainerLayoutParams = (RelativeLayout.LayoutParams) galleryFragmentContainer.getLayoutParams();

        messageInputLayoutParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        messageInputLayoutParams.addRule(RelativeLayout.ABOVE, R.id.galleryFragmentContainer);
        fragmentContainerLayoutParams.removeRule(RelativeLayout.BELOW);
        fragmentContainerLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);

        messageInput.setLayoutParams(messageInputLayoutParams);
        galleryFragmentContainer.setLayoutParams(fragmentContainerLayoutParams);
        galleryFragmentContainer.setVisibility(View.VISIBLE);

        AttachmentPopupFragment popupFragment = new AttachmentPopupFragment();
        Bundle popupArgs = new Bundle();

        popupArgs.putString(weMessage.ARG_CAMERA_ATTACHMENT_FILE, cameraAttachmentInput);
        popupArgs.putString(weMessage.ARG_VOICE_RECORDING_FILE, voiceMessageInput);
        popupArgs.putInt(weMessage.ARG_ATTACHMENT_POPUP_CAMERA_RESULT_CODE, resultCode);
        popupArgs.putParcelable(weMessage.ARG_ATTACHMENT_POPUP_CAMERA_INTENT, data);
        popupArgs.putStringArrayList(weMessage.ARG_ATTACHMENT_GALLERY_CACHE, new ArrayList<>(attachmentsInput));
        popupFragment.setArguments(popupArgs);

        getChildFragmentManager().beginTransaction().add(R.id.galleryFragmentContainer, popupFragment).commit();
    }

    private void closeAttachmentPopupFragment(){

        if (isPopupFragmentOpen) {
            isPopupFragmentOpen = false;

            RelativeLayout.LayoutParams messageInputLayoutParams = (RelativeLayout.LayoutParams) messageInput.getLayoutParams();
            RelativeLayout.LayoutParams fragmentContainerLayoutParams = (RelativeLayout.LayoutParams) galleryFragmentContainer.getLayoutParams();

            fragmentContainerLayoutParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            fragmentContainerLayoutParams.addRule(RelativeLayout.BELOW, R.id.messageInput);
            messageInputLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            messageInputLayoutParams.removeRule(RelativeLayout.ABOVE);

            messageInput.setLayoutParams(messageInputLayoutParams);
            galleryFragmentContainer.setLayoutParams(fragmentContainerLayoutParams);
            galleryFragmentContainer.setVisibility(View.GONE);

            getChildFragmentManager().beginTransaction().remove(getChildFragmentManager().findFragmentById(R.id.galleryFragmentContainer)).commit();
        }
    }

    private boolean isMessageBlocked(Message message){
        return message.getSender().isBlocked();
    }

    private void bindService(){
        Intent intent = new Intent(getActivity(), ConnectionService.class);
        getActivity().bindService(intent, serviceConnection, Context.BIND_IMPORTANT);
        isBoundToConnectionService = true;
    }

    private void unbindService(){
        if (isBoundToConnectionService) {
            getActivity().unbindService(serviceConnection);
            isBoundToConnectionService = false;
        }
    }

    private void showDisconnectReasonDialog(Intent bundledIntent, String defaultMessage, Runnable runnable){
        DialogDisplayer.showDisconnectReasonDialog(getContext(), getFragmentManager(), bundledIntent, defaultMessage, runnable);
    }

    private void clearEditText(final EditText editText, boolean closeKeyboard){
        if (closeKeyboard) {
            closeKeyboard();
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                editText.clearFocus();
            }
        }, 100);
    }

    private void closeKeyboard(){
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (getActivity().getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void showErroredSnackbar(String message){
        if (getView() != null) {
            final Snackbar snackbar = Snackbar.make(getView(), message, ERROR_SNACKBAR_DURATION * 1000);

            snackbar.setAction(getString(R.string.dismiss_button), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    snackbar.dismiss();
                }
            });
            snackbar.setActionTextColor(getResources().getColor(R.color.lightRed));
            snackbar.show();
        }
    }

    private boolean isChatThis(Chat c){
        return c.getUuid().toString().equals(getChat().getUuid().toString());
    }

    private void goToLauncher(){
        if (isAdded() || (getActivity() != null && !getActivity().isFinishing())) {
            Intent launcherIntent = new Intent(weMessage.get(), LaunchActivity.class);

            launcherIntent.putExtra(weMessage.BUNDLE_LAUNCHER_DO_NOT_TRY_RECONNECT, true);

            startActivity(launcherIntent);
            getActivity().finish();
        }
    }

    public void goToChatList(String reason){
        if (isAdded() || (getActivity() != null && !getActivity().isFinishing())) {
            Intent returnIntent = new Intent(weMessage.get(), ChatListActivity.class);

            if (reason != null) {
                returnIntent.putExtra(weMessage.BUNDLE_CONVERSATION_GO_BACK_REASON, reason);
            }

            startActivity(returnIntent);
            getActivity().finish();
        }
    }
}