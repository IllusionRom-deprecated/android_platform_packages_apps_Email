/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.Address;
import com.android.email.mail.MeetingInfo;
import com.android.email.mail.MessagingException;
import com.android.email.mail.PackedString;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.Message;
import com.android.email.service.EmailServiceConstants;

import org.apache.commons.io.IOUtils;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Browser;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.QuickContact;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageView extends Activity implements OnClickListener, MessageOrderManager.Callback {
    private static final String EXTRA_MESSAGE_ID = "com.android.email.MessageView_message_id";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.MessageView_mailbox_id";
    /* package */ static final String EXTRA_DISABLE_REPLY =
        "com.android.email.MessageView_disable_reply";

    // for saveInstanceState()
    private static final String STATE_MESSAGE_ID = "messageId";

    // Regex that matches start of img tag. '<(?i)img\s+'.
    private static final Pattern IMG_TAG_START_REGEX = Pattern.compile("<(?i)img\\s+");
    // Regex that matches Web URL protocol part as case insensitive.
    private static final Pattern WEB_URL_PROTOCOL = Pattern.compile("(?i)http|https://");

    private static int PREVIEW_ICON_WIDTH = 62;
    private static int PREVIEW_ICON_HEIGHT = 62;

    private TextView mSubjectView;
    private TextView mFromView;
    private TextView mDateView;
    private TextView mTimeView;
    private TextView mToView;
    private TextView mCcView;
    private View mCcContainerView;
    private WebView mMessageContentView;
    private LinearLayout mAttachments;
    private ImageView mAttachmentIcon;
    private ImageView mFavoriteIcon;
    private View mShowPicturesSection;
    private View mInviteSection;
    private ImageView mSenderPresenceView;
    private ProgressDialog mProgressDialog;
    private View mScrollView;

    // calendar meeting invite answers
    private TextView mMeetingYes;
    private TextView mMeetingMaybe;
    private TextView mMeetingNo;
    private int mPreviousMeetingResponse = -1;

    /**
     * If set, URI to the email (i.e. *.eml files, and possibly *.msg files) file that's being
     * viewed.
     *
     * Use {@link #isViewingEmailFile()} to see if the activity is created for opening an EML file.
     *
     * TODO: We probably should split it into two different MessageViews, one for regular messages
     * and the other for for EML files (these two will share the same base MessageView class) to
     * eliminate the bunch of 'if {@link #isViewingEmailFile()}'s.
     * Do this after making it into a fragment.
     */
    private Uri mFileEmailUri;
    private long mAccountId;
    private long mMessageId;
    private long mMailboxId;
    private Message mMessage;

    private LoadMessageTask mLoadMessageTask;
    private LoadBodyTask mLoadBodyTask;
    private LoadAttachmentsTask mLoadAttachmentsTask;
    private PresenceUpdater mPresenceUpdater;

    private MessageOrderManager mOrderManager;

    private long mLoadAttachmentId;         // the attachment being saved/viewed
    private boolean mLoadAttachmentSave;    // if true, saving - if false, viewing
    private String mLoadAttachmentName;     // the display name

    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;

    private Drawable mFavoriteIconOn;
    private Drawable mFavoriteIconOff;

    private Controller mController;
    private ControllerResultUiThreadWrapper<ControllerResults> mControllerCallback;

    private View mMoveToNewer;
    private View mMoveToOlder;

    // contains the HTML body. Is used by LoadAttachmentTask to display inline images.
    // is null most of the time, is used transiently to pass info to LoadAttachementTask
    private String mHtmlTextRaw;

    // contains the HTML content as set in WebView.
    private String mHtmlTextWebView;

    // this is true when reply & forward are disabled, such as messages in the trash
    private boolean mDisableReplyAndForward;

   /**
     * Encapsulates known information about a single attachment.
     */
    private static class AttachmentInfo {
        public String name;
        public String contentType;
        public long size;
        public long attachmentId;
        public Button viewButton;
        public Button downloadButton;
        public ImageView iconView;
    }

    /**
     * View a specific message found in the Email provider.
     * @param messageId the message to view.
     * @param mailboxId identifies the sequence of messages used for newer/older navigation.
     * @param disableReplyAndForward set if reply/forward do not make sense for this message
     *        (e.g. messages in Trash).
     */
    public static void actionView(Context context, long messageId, long mailboxId,
            boolean disableReplyAndForward) {
        if (messageId < 0) {
            throw new IllegalArgumentException("MessageView invalid messageId " + messageId);
        }
        Intent i = new Intent(context, MessageView.class);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        i.putExtra(EXTRA_DISABLE_REPLY, disableReplyAndForward);
        context.startActivity(i);
    }

    public static void actionView(Context context, long messageId, long mailboxId) {
        actionView(context, messageId, mailboxId, false);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.message_view);

        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
        mPresenceUpdater = new PresenceUpdater(this);

        mSubjectView = (TextView) findViewById(R.id.subject);
        mFromView = (TextView) findViewById(R.id.from);
        mToView = (TextView) findViewById(R.id.to);
        mCcView = (TextView) findViewById(R.id.cc);
        mCcContainerView = findViewById(R.id.cc_container);
        mDateView = (TextView) findViewById(R.id.date);
        mTimeView = (TextView) findViewById(R.id.time);
        mMessageContentView = (WebView) findViewById(R.id.message_content);
        mAttachments = (LinearLayout) findViewById(R.id.attachments);
        mAttachmentIcon = (ImageView) findViewById(R.id.attachment);
        mFavoriteIcon = (ImageView) findViewById(R.id.favorite);
        mShowPicturesSection = findViewById(R.id.show_pictures_section);
        mInviteSection = findViewById(R.id.invite_section);
        mSenderPresenceView = (ImageView) findViewById(R.id.presence);
        mMoveToNewer = findViewById(R.id.moveToNewer);
        mMoveToOlder = findViewById(R.id.moveToOlder);
        mScrollView = findViewById(R.id.scrollview);

        mMoveToNewer.setOnClickListener(this);
        mMoveToOlder.setOnClickListener(this);
        mFromView.setOnClickListener(this);
        mSenderPresenceView.setOnClickListener(this);
        mFavoriteIcon.setOnClickListener(this);
        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);
        findViewById(R.id.show_pictures).setOnClickListener(this);

        mMeetingYes = (TextView) findViewById(R.id.accept);
        mMeetingMaybe = (TextView) findViewById(R.id.maybe);
        mMeetingNo = (TextView) findViewById(R.id.decline);

        mMeetingYes.setOnClickListener(this);
        mMeetingMaybe.setOnClickListener(this);
        mMeetingNo.setOnClickListener(this);
        findViewById(R.id.invite_link).setOnClickListener(this);

        mMessageContentView.setVerticalScrollBarEnabled(false);
        mMessageContentView.getSettings().setBlockNetworkLoads(true);
        mMessageContentView.getSettings().setSupportZoom(false);
        mMessageContentView.setWebViewClient(new CustomWebViewClient());

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);

        mDateFormat = android.text.format.DateFormat.getDateFormat(this);   // short format
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);   // 12/24 date format

        mFavoriteIconOn = getResources().getDrawable(R.drawable.btn_star_big_buttonless_on);
        mFavoriteIconOff = getResources().getDrawable(R.drawable.btn_star_big_buttonless_off);

        initFromIntent();
        if (icicle != null) {
            mMessageId = icicle.getLong(STATE_MESSAGE_ID, mMessageId);
        }

        mController = Controller.getInstance(getApplication());
        messageChanged();
    }

    /* package */ void initFromIntent() {
        Intent intent = getIntent();
        mFileEmailUri = intent.getData();
        if (mFileEmailUri == null) {
            mMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);
            mMailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, -1);
        } else {
            mMessageId = -1;
            mMailboxId = -1;
        }
        mDisableReplyAndForward = intent.getBooleanExtra(EXTRA_DISABLE_REPLY, false)
                || isViewingEmailFile();
        if (mDisableReplyAndForward) {
            findViewById(R.id.reply).setEnabled(false);
            findViewById(R.id.reply_all).setEnabled(false);
        }
        if (isViewingEmailFile()) {
            // TODO set title here: "Viewing XXX.eml".
            findViewById(R.id.delete).setEnabled(false);
            findViewById(R.id.favorite).setVisibility(View.GONE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (mMessageId != -1) {
            state.putLong(STATE_MESSAGE_ID, mMessageId);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mController.addResultCallback(mControllerCallback);

        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }

        if (mMessage != null) {
            startPresenceCheck();
        }
        if (!isViewingEmailFile()) {
            mOrderManager = new MessageOrderManager(this, mMailboxId, this);
        }
    }

    @Override
    public void onPause() {
        mController.removeResultCallback(mControllerCallback);
        if (mOrderManager != null) {
            mOrderManager.close();
            mOrderManager = null;
        }
        super.onPause();
    }

    private void cancelAllTasks() {
        Utility.cancelTaskInterrupt(mLoadMessageTask);
        mLoadMessageTask = null;
        Utility.cancelTaskInterrupt(mLoadBodyTask);
        mLoadBodyTask = null;
        Utility.cancelTaskInterrupt(mLoadAttachmentsTask);
        mLoadAttachmentsTask = null;
        mPresenceUpdater.cancelAll();
    }

    /**
     * We override onDestroy to make sure that the WebView gets explicitly destroyed.
     * Otherwise it can leak native references.
     */
    @Override
    public void onDestroy() {
        if (mOrderManager != null) {
            mOrderManager.close();
        }
        cancelAllTasks();
        mMessageContentView.destroy();
        mMessageContentView = null;
        // the cursor was closed in onPause()
        // If we're leaving a non-attachment message, delete any/all attachment messages
        if (!isViewingEmailFile()) {
            mController.deleteAttachmentMessages();
        }
        super.onDestroy();
    }

    /**
     * @return true if viewing an email file.  (i.e. *.eml files)
     */
    private boolean isViewingEmailFile() {
        return mFileEmailUri != null;
    }

    /**
     * Overrides for various WebView behaviors.
     */
    private class CustomWebViewClient extends WebViewClient {
        /**
         * This is intended to mirror the operation of the original
         * (see android.webkit.CallbackProxy) with one addition of intent flags
         * "FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET".  This improves behavior when sublaunching
         * other apps via embedded URI's.
         *
         * We also use this hook to catch "mailto:" links and handle them locally.
         */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // hijack mailto: uri's and handle locally
            if (url != null && url.toLowerCase().startsWith("mailto:")) {
                return MessageCompose.actionCompose(MessageView.this, url, mAccountId);
            }

            // Handle most uri's via intent launch
            boolean result = false;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, getPackageName());
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
            try {
                startActivity(intent);
                result = true;
            } catch (ActivityNotFoundException ex) {
                // If no application can handle the URL, assume that the
                // caller can handle it.
            }
            return result;
        }
    }

    /**
     * Handle clicks on sender, which shows {@link QuickContact} or prompts to add
     * the sender as a contact.
     */
    private void onClickSender() {
        // Bail early if message or sender not present
        if (mMessage == null) return;

        final Address senderEmail = Address.unpackFirst(mMessage.mFrom);
        if (senderEmail == null) return;

        // First perform lookup query to find existing contact
        final ContentResolver resolver = getContentResolver();
        final String address = senderEmail.getAddress();
        final Uri dataUri = Uri.withAppendedPath(CommonDataKinds.Email.CONTENT_FILTER_URI,
                Uri.encode(address));
        final Uri lookupUri = ContactsContract.Data.getContactLookupUri(resolver, dataUri);

        if (lookupUri != null) {
            // Found matching contact, trigger QuickContact
            QuickContact.showQuickContact(this, mSenderPresenceView, lookupUri,
                    QuickContact.MODE_LARGE, null);
        } else {
            // No matching contact, ask user to create one
            final Uri mailUri = Uri.fromParts("mailto", address, null);
            final Intent intent = new Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT,
                    mailUri);

            // Pass along full E-mail string for possible create dialog
            intent.putExtra(ContactsContract.Intents.EXTRA_CREATE_DESCRIPTION,
                    senderEmail.toString());

            // Only provide personal name hint if we have one
            final String senderPersonal = senderEmail.getPersonal();
            if (!TextUtils.isEmpty(senderPersonal)) {
                intent.putExtra(ContactsContract.Intents.Insert.NAME, senderPersonal);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);

            startActivity(intent);
        }
    }

    /**
     * Toggle favorite status and write back to provider
     */
    private void onClickFavorite() {
        if (isViewingEmailFile()) {
            return;
        }
        if (mMessage != null) {
            // Update UI
            boolean newFavorite = ! mMessage.mFlagFavorite;
            mFavoriteIcon.setImageDrawable(newFavorite ? mFavoriteIconOn : mFavoriteIconOff);

            // Update provider
            mMessage.mFlagFavorite = newFavorite;
            mController.setMessageFavorite(mMessageId, newFavorite);
        }
    }

    private void onReply() {
        if (isViewingEmailFile()) {
            return;
        }
        if (mMessage != null) {
            MessageCompose.actionReply(this, mMessage.mId, false);
            finish();
        }
    }

    private void onReplyAll() {
        if (isViewingEmailFile()) {
            return;
        }
        if (mMessage != null) {
            MessageCompose.actionReply(this, mMessage.mId, true);
            finish();
        }
    }

    private void onForward() {
        if (isViewingEmailFile()) {
            return;
        }
        if (mMessage != null) {
            MessageCompose.actionForward(this, mMessage.mId);
            finish();
        }
    }

    private void onDeleteMessage() {
        if (isViewingEmailFile()) {
            return;
        }
        if (mMessage != null) {
            // the delete triggers mCursorObserver
            // first move to older/newer before the actual delete
            long messageIdToDelete = mMessageId;
            boolean moved = moveToOlder() || moveToNewer();
            mController.deleteMessage(messageIdToDelete, mMessage.mAccountKey);
            Utility.showToast(this,
                    getResources().getQuantityString(R.plurals.message_deleted_toast, 1));
            if (!moved) {
                // this generates a benign warning "Duplicate finish request" because
                // repositionMessageListCursor() will fail to reposition and do its own finish()
                finish();
            }
        }
    }

    private boolean moveToOlder() {
        if (isViewingEmailFile()) {
            return false;
        }
        // Guard with !isLast() because Cursor.moveToNext() returns false even as it moves
        // from last to after-last.
        if (mOrderManager != null && mOrderManager.moveToOlder()) {
            mMessageId = mOrderManager.getCurrentMessageId();
            messageChanged();
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if (isViewingEmailFile()) {
            return false;
        }
        // Guard with !isFirst() because Cursor.moveToPrev() returns false even as it moves
        // from first to before-first.
        if (mOrderManager != null && mOrderManager.moveToNewer()) {
            mMessageId = mOrderManager.getCurrentMessageId();
            messageChanged();
            return true;
        }
        return false;
    }

    private void onMarkAsRead(boolean isRead) {
        if (isViewingEmailFile()) {
            return;
        }
        if (mMessage != null && mMessage.mFlagRead != isRead) {
            mMessage.mFlagRead = isRead;
            mController.setMessageRead(mMessageId, isRead);
        }
    }

    /**
     * Send a service message indicating that a meeting invite button has been clicked.
     */
    private void onRespondToInvite(int response, int toastResId) {
        if (isViewingEmailFile()) {
            return;
        }
        // do not send twice in a row the same response
        if (mPreviousMeetingResponse != response) {
            mController.sendMeetingResponse(mMessageId, response);
            mPreviousMeetingResponse = response;
        }
        Toast.makeText(this, toastResId, Toast.LENGTH_SHORT).show();
        if (!moveToOlder()) {
            finish(); // if this is the last message, move up to message-list.
        }
    }

    private void onDownloadAttachment(AttachmentInfo attachment) {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            /*
             * Abort early if there's no place to save the attachment. We don't want to spend
             * the time downloading it and then abort.
             */
            Toast.makeText(this,
                    getString(R.string.message_view_status_attachment_not_saved),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mLoadAttachmentId = attachment.attachmentId;
        mLoadAttachmentSave = true;
        mLoadAttachmentName = attachment.name;

        mController.loadAttachment(attachment.attachmentId, mMessageId, mMessage.mMailboxKey,
                mAccountId);
    }

    private void onViewAttachment(AttachmentInfo attachment) {
        mLoadAttachmentId = attachment.attachmentId;
        mLoadAttachmentSave = false;
        mLoadAttachmentName = attachment.name;

        mController.loadAttachment(attachment.attachmentId, mMessageId, mMessage.mMailboxKey,
                mAccountId);
    }

    private void onShowPicturesInHtml() {
        if (mMessage != null) {
            if (mMessageContentView != null) {
                mMessageContentView.getSettings().setBlockNetworkLoads(false);
                if (mHtmlTextWebView != null) {
                    mMessageContentView.loadDataWithBaseURL("email://", mHtmlTextWebView,
                                                            "text/html", "utf-8", null);
                }
            }
            mShowPicturesSection.setVisibility(View.GONE);
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.from:
            case R.id.presence:
                onClickSender();
                break;
            case R.id.favorite:
                onClickFavorite();
                break;
            case R.id.reply:
                onReply();
                break;
            case R.id.reply_all:
                onReplyAll();
                break;
            case R.id.delete:
                onDeleteMessage();
                break;
            case R.id.moveToOlder:
                moveToOlder();
                break;
            case R.id.moveToNewer:
                moveToNewer();
                break;
            case R.id.download:
                onDownloadAttachment((AttachmentInfo) view.getTag());
                break;
            case R.id.view:
                onViewAttachment((AttachmentInfo) view.getTag());
                break;
            case R.id.show_pictures:
                onShowPicturesInHtml();
                break;
            case R.id.accept:
                onRespondToInvite(EmailServiceConstants.MEETING_REQUEST_ACCEPTED,
                         R.string.message_view_invite_toast_yes);
                break;
            case R.id.maybe:
                onRespondToInvite(EmailServiceConstants.MEETING_REQUEST_TENTATIVE,
                         R.string.message_view_invite_toast_maybe);
                break;
            case R.id.decline:
                onRespondToInvite(EmailServiceConstants.MEETING_REQUEST_DECLINED,
                         R.string.message_view_invite_toast_no);
                break;
            case R.id.invite_link:
                onInviteLinkClicked();
                break;
        }
    }

    private void onInviteLinkClicked() {
        if (isViewingEmailFile()) {
            return;
        }
        String startTime = new PackedString(mMessage.mMeetingInfo).get(MeetingInfo.MEETING_DTSTART);
        if (startTime != null) {
            long epochTimeMillis = Utility.parseEmailDateTimeToMillis(startTime);
            Uri uri = Uri.parse("content://com.android.calendar/time/" + epochTimeMillis);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(uri);
            intent.putExtra("VIEW", "DAY");
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
            startActivity(intent);
        } else {
            Email.log("meetingInfo without DTSTART " + mMessage.mMeetingInfo);
        }
    }

   @Override
    public boolean onOptionsItemSelected(MenuItem item) {
       boolean handled = handleMenuItem(item.getItemId());
       if (!handled) {
           handled = super.onOptionsItemSelected(item);
       }
       return handled;
   }

   /**
    * This is the core functionality of onOptionsItemSelected() but broken out and exposed
    * for testing purposes (because it's annoying to mock a MenuItem).
    *
    * @param menuItemId id that was clicked
    * @return true if handled here
    */
   /* package */ boolean handleMenuItem(int menuItemId) {
       switch (menuItemId) {
           case R.id.delete:
               onDeleteMessage();
               break;
           case R.id.reply:
               onReply();
               break;
           case R.id.reply_all:
               onReplyAll();
               break;
           case R.id.forward:
               onForward();
               break;
           case R.id.mark_as_unread:
               onMarkAsRead(false);
               finish();
               break;
           default:
               return false;
       }
       return true;
   }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        if (isViewingEmailFile()) {
            return false; // No menu options for EML files.
        }
        getMenuInflater().inflate(R.menu.message_view_option, menu);
        if (mDisableReplyAndForward) {
            menu.findItem(R.id.forward).setEnabled(false);
            menu.findItem(R.id.reply).setEnabled(false);
            menu.findItem(R.id.reply_all).setEnabled(false);
        }
        return true;
    }

    /**
     * Re-init everything needed for changing message.
     */
    private void messageChanged() {
        if (Email.DEBUG) {
            Email.log("MessageView: messageChanged to id=" + mMessageId);
        }
        cancelAllTasks();
        setTitle("");
        if (mMessageContentView != null) {
            mMessageContentView.scrollTo(0, 0);
            mMessageContentView.loadUrl("file:///android_asset/empty.html");
        }
        mScrollView.scrollTo(0, 0);
        mAttachments.removeAllViews();
        mAttachments.setVisibility(View.GONE);
        mAttachmentIcon.setVisibility(View.GONE);

        // Start an AsyncTask to make a new cursor and load the message
        mLoadMessageTask = new LoadMessageTask(mFileEmailUri, mMessageId, true);
        mLoadMessageTask.execute();
        updateNavigationArrows();
    }

    /**
     * Update the arrows based on the current position of the older/newer cursor.
     */
    private void updateNavigationArrows() {
        mMoveToNewer.setVisibility((mOrderManager != null) && mOrderManager.canMoveToNewer()
                ? View.VISIBLE : View.INVISIBLE);
        mMoveToOlder.setVisibility((mOrderManager != null) && mOrderManager.canMoveToOlder()
                ? View.VISIBLE : View.INVISIBLE);
    }

    private Bitmap getPreviewIcon(AttachmentInfo attachment) {
        try {
            return BitmapFactory.decodeStream(
                    getContentResolver().openInputStream(
                            AttachmentProvider.getAttachmentThumbnailUri(
                                    mAccountId, attachment.attachmentId,
                                    PREVIEW_ICON_WIDTH,
                                    PREVIEW_ICON_HEIGHT)));
        } catch (Exception e) {
            /*
             * We don't care what happened, we just return null for the preview icon.
             */
            return null;
        }
    }

    private void updateAttachmentThumbnail(long attachmentId) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            AttachmentInfo attachment = (AttachmentInfo) mAttachments.getChildAt(i).getTag();
            if (attachment.attachmentId == attachmentId) {
                Bitmap previewIcon = getPreviewIcon(attachment);
                if (previewIcon != null) {
                    attachment.iconView.setImageBitmap(previewIcon);
                }
                return;
            }
        }
    }

    /**
     * Copy data from a cursor-refreshed attachment into the UI.  Called from UI thread.
     *
     * @param attachment A single attachment loaded from the provider
     */
    private void addAttachment(Attachment attachment) {
        AttachmentInfo attachmentInfo = new AttachmentInfo();
        attachmentInfo.size = attachment.mSize;
        attachmentInfo.contentType =
                AttachmentProvider.inferMimeType(attachment.mFileName, attachment.mMimeType);
        attachmentInfo.name = attachment.mFileName;
        attachmentInfo.attachmentId = attachment.mId;

        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.message_view_attachment, null);

        TextView attachmentName = (TextView)view.findViewById(R.id.attachment_name);
        TextView attachmentInfoView = (TextView)view.findViewById(R.id.attachment_info);
        ImageView attachmentIcon = (ImageView)view.findViewById(R.id.attachment_icon);
        Button attachmentView = (Button)view.findViewById(R.id.view);
        Button attachmentDownload = (Button)view.findViewById(R.id.download);

        if ((!MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                Email.ACCEPTABLE_ATTACHMENT_VIEW_TYPES))
                || (MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                        Email.UNACCEPTABLE_ATTACHMENT_VIEW_TYPES))) {
            attachmentView.setVisibility(View.GONE);
        }
        if ((!MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                Email.ACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES))
                || (MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                        Email.UNACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES))) {
            attachmentDownload.setVisibility(View.GONE);
        }

        if (attachmentInfo.size > Email.MAX_ATTACHMENT_DOWNLOAD_SIZE) {
            attachmentView.setVisibility(View.GONE);
            attachmentDownload.setVisibility(View.GONE);
        }

        attachmentInfo.viewButton = attachmentView;
        attachmentInfo.downloadButton = attachmentDownload;
        attachmentInfo.iconView = attachmentIcon;

        view.setTag(attachmentInfo);
        attachmentView.setOnClickListener(this);
        attachmentView.setTag(attachmentInfo);
        attachmentDownload.setOnClickListener(this);
        attachmentDownload.setTag(attachmentInfo);

        attachmentName.setText(attachmentInfo.name);
        attachmentInfoView.setText(Utility.formatSize(this, attachmentInfo.size));

        Bitmap previewIcon = getPreviewIcon(attachmentInfo);
        if (previewIcon != null) {
            attachmentIcon.setImageBitmap(previewIcon);
        }

        mAttachments.addView(view);
        mAttachments.setVisibility(View.VISIBLE);
    }

    /**
     * Start checking presence of the sender of the message.
     *
     * This must only be called when mMessage is null (it will hide presence indications) or when
     * mMessage has already seen its headers loaded.
     *
     * Note:  This is just a polling operation.  A more advanced solution would be to keep the
     * cursor open and respond to presence status updates (in the form of content change
     * notifications).  However, because presence changes fairly slowly compared to the duration
     * of viewing a single message, a simple poll at message load (and onResume) should be
     * sufficient.
     */
    private void startPresenceCheck() {
        // Set "unknown" presence icon.
        mSenderPresenceView.setImageResource(PresenceUpdater.getPresenceIconResourceId(null));
        if (mMessage != null) {
            Address sender = Address.unpackFirst(mMessage.mFrom);
            if (sender != null) {
                String email = sender.getAddress();
                if (email != null) {
                    mPresenceUpdater.checkPresence(email, new PresenceUpdater.Callback() {
                        @Override
                        public void onPresenceResult(String emailAddress, Integer presenceStatus) {
                            mSenderPresenceView.setImageResource(
                                    PresenceUpdater.getPresenceIconResourceId(presenceStatus));
                        }
                    });
                }
            }
        }
    }

    /**
     * Async task for loading a single message outside of the UI thread
     */
    private class LoadMessageTask extends AsyncTask<Void, Void, Message> {

        private final long mId;
        private final boolean mOkToFetch;
        private final Uri mFileEmailUri;

        /**
         * Special constructor to cache some local info
         */
        public LoadMessageTask(Uri fileEmailUri, long messageId, boolean okToFetch) {
            mFileEmailUri = fileEmailUri;
            mId = messageId;
            mOkToFetch = okToFetch;
        }

        /**
         * There will either be a Uri in the Intent (i.e. whose content is the Message to be
         * loaded), or mId will be holding the id of the Message as stored in the provider.
         * If we're loading via Uri, the Controller does the actual message parsing and storage,
         * and we setup the message id and mailbox id based on the result; forward and reply are
         * disabled for messages loaded via Uri
         */
        @Override
        protected Message doInBackground(Void... params) {
            // If we have a URI, then we were opened via an intent filter (e.g. an attachment that
            // has a mime type that we can handle (e.g. message/rfc822).
            if (mFileEmailUri != null) {
                final Activity activity = MessageView.this;
                // Put up a toast; this can take a little while...
                Utility.showToast(activity, R.string.message_view_parse_message_toast);
                Message msg = mController.loadMessageFromUri(mFileEmailUri);
                if (msg == null) {
                    // Indicate that the attachment couldn't be loaded
                    Utility.showToast(activity, R.string.message_view_display_attachment_toast);
                    return null;
                }
                return msg;
            }
            return Message.restoreMessageWithId(MessageView.this, mId);
        }

        @Override
        protected void onPostExecute(Message message) {
            /* doInBackground() may return null result (due to restoreMessageWithId())
             * and in that situation we want to Activity.finish().
             *
             * OTOH we don't want to Activity.finish() for isCancelled() because this
             * would introduce a surprise side-effect to task cancellation: every task
             * cancelation would also result in finish().
             *
             * Right now LoadMesageTask is cancelled not only from onDestroy(),
             * and it would be a bug to also finish() the activity in that situation.
             */
            if (isCancelled()) {
                return;
            }
            if (message == null) {
                finish();
                return;
            }
            mMessageId = message.mId;
            mMailboxId = message.mMailboxKey;
            reloadUiFromMessage(message, mOkToFetch);
            startPresenceCheck();
        }
    }

    /**
     * Async task for loading a single message body outside of the UI thread
     */
    private class LoadBodyTask extends AsyncTask<Void, Void, String[]> {

        private long mId;
        private boolean mErrorLoadingMessageBody;

        /**
         * Special constructor to cache some local info
         */
        public LoadBodyTask(long messageId) {
            mId = messageId;
        }

        @Override
        protected String[] doInBackground(Void... params) {
            try {
                String text = null;
                String html = Body.restoreBodyHtmlWithMessageId(MessageView.this, mId);
                if (html == null) {
                    text = Body.restoreBodyTextWithMessageId(MessageView.this, mId);
                }
                return new String[] { text, html };
            } catch (RuntimeException re) {
                // This catches SQLiteException as well as other RTE's we've seen from the
                // database calls, such as IllegalStateException
                Log.d(Email.LOG_TAG, "Exception while loading message body: " + re.toString());
                mErrorLoadingMessageBody = true;
                return new String[] { null, null };
            }
        }

        @Override
        protected void onPostExecute(String[] results) {
            if (results == null) {
                if (mErrorLoadingMessageBody) {
                    Utility.showToast(MessageView.this, R.string.error_loading_message_body);
                }
                return;
            }
            reloadUiFromBody(results[0], results[1]);    // text, html
            onMarkAsRead(true);
        }
    }

    /**
     * Async task for loading attachments
     *
     * Note:  This really should only be called when the message load is complete - or, we should
     * leave open a listener so the attachments can fill in as they are discovered.  In either case,
     * this implementation is incomplete, as it will fail to refresh properly if the message is
     * partially loaded at this time.
     */
    private class LoadAttachmentsTask extends AsyncTask<Long, Void, Attachment[]> {
        @Override
        protected Attachment[] doInBackground(Long... messageIds) {
            return Attachment.restoreAttachmentsWithMessageId(MessageView.this, messageIds[0]);
        }

        @Override
        protected void onPostExecute(Attachment[] attachments) {
            if (attachments == null) {
                return;
            }
            boolean htmlChanged = false;
            for (Attachment attachment : attachments) {
                if (mHtmlTextRaw != null && attachment.mContentId != null
                        && attachment.mContentUri != null) {
                    // for html body, replace CID for inline images
                    // Regexp which matches ' src="cid:contentId"'.
                    String contentIdRe =
                        "\\s+(?i)src=\"cid(?-i):\\Q" + attachment.mContentId + "\\E\"";
                    String srcContentUri = " src=\"" + attachment.mContentUri + "\"";
                    mHtmlTextRaw = mHtmlTextRaw.replaceAll(contentIdRe, srcContentUri);
                    htmlChanged = true;
                } else {
                    addAttachment(attachment);
                }
            }
            mHtmlTextWebView = mHtmlTextRaw;
            mHtmlTextRaw = null;
            if (htmlChanged && mMessageContentView != null) {
                mMessageContentView.loadDataWithBaseURL("email://", mHtmlTextWebView,
                                                        "text/html", "utf-8", null);
            }
        }
    }

    /**
     * Reload the UI from a provider cursor.  This must only be called from the UI thread.
     *
     * @param message A copy of the message loaded from the database
     * @param okToFetch If true, and message is not fully loaded, it's OK to fetch from
     * the network.  Use false to prevent looping here.
     *
     * TODO: trigger presence check
     */
    private void reloadUiFromMessage(Message message, boolean okToFetch) {
        mMessage = message;
        mAccountId = message.mAccountKey;
        if (mMailboxId == -1) {
            mMailboxId = message.mMailboxKey;
        }

        if (mOrderManager != null) {
            mOrderManager.moveTo(message.mId);
        }

        mSubjectView.setText(message.mSubject);
        mFromView.setText(Address.toFriendly(Address.unpack(message.mFrom)));
        Date date = new Date(message.mTimeStamp);
        mTimeView.setText(mTimeFormat.format(date));
        mDateView.setText(Utility.isDateToday(date) ? null : mDateFormat.format(date));
        mToView.setText(Address.toFriendly(Address.unpack(message.mTo)));
        String friendlyCc = Address.toFriendly(Address.unpack(message.mCc));
        mCcView.setText(friendlyCc);
        mCcContainerView.setVisibility((friendlyCc != null) ? View.VISIBLE : View.GONE);
        mAttachmentIcon.setVisibility(message.mAttachments != null ? View.VISIBLE : View.GONE);
        mFavoriteIcon.setImageDrawable(message.mFlagFavorite ? mFavoriteIconOn : mFavoriteIconOff);
        // Show the message invite section if we're an incoming meeting invitation only
        mInviteSection.setVisibility((message.mFlags & Message.FLAG_INCOMING_MEETING_INVITE) != 0 ?
                View.VISIBLE : View.GONE);

        // Handle partially-loaded email, as follows:
        // 1. Check value of message.mFlagLoaded
        // 2. If != LOADED, ask controller to load it
        // 3. Controller callback (after loaded) should trigger LoadBodyTask & LoadAttachmentsTask
        // 4. Else start the loader tasks right away (message already loaded)
        if (okToFetch && message.mFlagLoaded != Message.FLAG_LOADED_COMPLETE) {
            mControllerCallback.getWrappee().setWaitForLoadMessageId(message.mId);
            mController.loadMessageForView(message.mId);
        } else {
            mControllerCallback.getWrappee().setWaitForLoadMessageId(-1);
            // Ask for body
            mLoadBodyTask = new LoadBodyTask(message.mId);
            mLoadBodyTask.execute();
        }
    }

    /**
     * Reload the body from the provider cursor.  This must only be called from the UI thread.
     *
     * @param bodyText text part
     * @param bodyHtml html part
     *
     * TODO deal with html vs text and many other issues
     */
    private void reloadUiFromBody(String bodyText, String bodyHtml) {
        String text = null;
        mHtmlTextRaw = null;
        boolean hasImages = false;

        if (bodyHtml == null) {
            text = bodyText;
            /*
             * Convert the plain text to HTML
             */
            StringBuffer sb = new StringBuffer("<html><body>");
            if (text != null) {
                // Escape any inadvertent HTML in the text message
                text = EmailHtmlUtil.escapeCharacterToDisplay(text);
                // Find any embedded URL's and linkify
                Matcher m = Patterns.WEB_URL.matcher(text);
                while (m.find()) {
                    int start = m.start();
                    /*
                     * WEB_URL_PATTERN may match domain part of email address. To detect
                     * this false match, the character just before the matched string
                     * should not be '@'.
                     */
                    if (start == 0 || text.charAt(start - 1) != '@') {
                        String url = m.group();
                        Matcher proto = WEB_URL_PROTOCOL.matcher(url);
                        String link;
                        if (proto.find()) {
                            // This is work around to force URL protocol part be lower case,
                            // because WebView could follow only lower case protocol link.
                            link = proto.group().toLowerCase() + url.substring(proto.end());
                        } else {
                            // Patterns.WEB_URL matches URL without protocol part,
                            // so added default protocol to link.
                            link = "http://" + url;
                        }
                        String href = String.format("<a href=\"%s\">%s</a>", link, url);
                        m.appendReplacement(sb, href);
                    }
                    else {
                        m.appendReplacement(sb, "$0");
                    }
                }
                m.appendTail(sb);
            }
            sb.append("</body></html>");
            text = sb.toString();
        } else {
            text = bodyHtml;
            mHtmlTextRaw = bodyHtml;
            hasImages = IMG_TAG_START_REGEX.matcher(text).find();
        }

        mShowPicturesSection.setVisibility(hasImages ? View.VISIBLE : View.GONE);
        if (mMessageContentView != null) {
            mMessageContentView.loadDataWithBaseURL("email://", text, "text/html", "utf-8", null);
        }

        // Ask for attachments after body
        mLoadAttachmentsTask = new LoadAttachmentsTask();
        mLoadAttachmentsTask.execute(mMessage.mId);
    }

    /**
     * Controller results listener.  We wrap it with {@link ControllerResultUiThreadWrapper},
     * so all methods are called on the UI thread.
     */
    private class ControllerResults extends Controller.Result {
        private long mWaitForLoadMessageId;

        public void setWaitForLoadMessageId(long messageId) {
            mWaitForLoadMessageId = messageId;
        }

        @Override
        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress) {
            if (messageId != mWaitForLoadMessageId) {
                // We are not waiting for this message to load, so exit quickly
                return;
            }
            if (result == null) {
                switch (progress) {
                    case 0:
                        setProgressBarIndeterminateVisibility(true);
                        loadBodyContent("file:///android_asset/loading.html");
                        break;
                    case 100:
                        mWaitForLoadMessageId = -1;
                        setProgressBarIndeterminateVisibility(false);
                        // reload UI and reload everything else too
                        // pass false to LoadMessageTask to prevent looping here
                        cancelAllTasks();
                        mLoadMessageTask = new LoadMessageTask(mFileEmailUri, mMessageId, false);
                        mLoadMessageTask.execute();
                        break;
                    default:
                        // do nothing - we don't have a progress bar at this time
                        break;
                }
            } else {
                mWaitForLoadMessageId = -1;
                setProgressBarIndeterminateVisibility(false);
                Utility.showToast(MessageView.this, R.string.status_network_error);
                loadBodyContent("file:///android_asset/empty.html");
            }
        }

        private void loadBodyContent(String uri) {
            if (mMessageContentView != null) {
                mMessageContentView.loadUrl(uri);
            }
        }

        @Override
        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
            if (messageId == MessageView.this.mMessageId) {
                if (result == null) {
                    switch (progress) {
                        case 0:
                            enableAttachments(false);
                            showFetchingAttachmentProgress(true);
                            Utility.showToast(MessageView.this,
                                    R.string.message_view_fetching_attachment_toast);
                            break;
                        case 100:
                            enableAttachments(true);
                            showFetchingAttachmentProgress(false);
                            updateAttachmentThumbnail(attachmentId);
                            doFinishLoadAttachment(attachmentId);
                            break;
                        default:
                            // do nothing - we don't have a progress bar at this time
                            break;
                    }
                } else {
                    enableAttachments(true);
                    showFetchingAttachmentProgress(false);
                    Utility.showToast(MessageView.this, R.string.status_network_error);
                }
            }
        }

        private void enableAttachments(boolean enable) {
            for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
                AttachmentInfo attachment = (AttachmentInfo) mAttachments.getChildAt(i).getTag();
                attachment.viewButton.setEnabled(enable);
                attachment.downloadButton.setEnabled(enable);
            }
        }

        private void showFetchingAttachmentProgress(boolean show) {
            if (show) {
                mProgressDialog.setMessage(
                        getString(R.string.message_view_fetching_attachment_progress,
                                mLoadAttachmentName));
                mProgressDialog.show();
            } else {
                mProgressDialog.dismiss();
            }
            setProgressBarIndeterminateVisibility(show);
        }

        @Override
        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mailboxId);
            }
        }
    }

    /**
     * Back in the UI thread, handle the final steps of downloading an attachment (view or save).
     *
     * @param attachmentId the attachment that was just downloaded
     */
    private void doFinishLoadAttachment(long attachmentId) {
        // If the result does't line up, just skip it - we handle one at a time.
        if (attachmentId != mLoadAttachmentId) {
            return;
        }
        Attachment attachment =
            Attachment.restoreAttachmentWithId(MessageView.this, attachmentId);
        Uri attachmentUri = AttachmentProvider.getAttachmentUri(mAccountId, attachment.mId);
        Uri contentUri =
            AttachmentProvider.resolveAttachmentIdToContentUri(getContentResolver(), attachmentUri);

        if (mLoadAttachmentSave) {
            try {
                File file = Utility.createUniqueFile(Environment.getExternalStorageDirectory(),
                        attachment.mFileName);
                InputStream in = getContentResolver().openInputStream(contentUri);
                OutputStream out = new FileOutputStream(file);
                IOUtils.copy(in, out);
                out.flush();
                out.close();
                in.close();

                Utility.showToast(this, String.format(
                        getString(R.string.message_view_status_attachment_saved), file.getName()));

                new MediaScannerNotifier(this, file);
            } catch (IOException ioe) {
                Utility.showToast(this,
                        getString(R.string.message_view_status_attachment_not_saved));
            }
        } else {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(contentUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Utility.showToast(this, R.string.message_view_display_attachment_toast);
                // TODO: Add a proper warning message (and lots of upstream cleanup to prevent
                // it from happening) in the next release.
            }
        }
    }

    /**
     * This notifier is created after an attachment completes downloaded.  It attaches to the
     * media scanner and waits to handle the completion of the scan.  At that point it tries
     * to start an ACTION_VIEW activity for the attachment.
    */
    private static class MediaScannerNotifier implements MediaScannerConnectionClient {
        private Activity mActivity;
        private MediaScannerConnection mConnection;
        private File mFile;

        public MediaScannerNotifier(Activity activity, File file) {
            mActivity = activity;
            mFile = file;
            mConnection = new MediaScannerConnection(mActivity.getApplicationContext(), this);
            mConnection.connect();
        }

        public void onMediaScannerConnected() {
            mConnection.scanFile(mFile.getAbsolutePath(), null);
        }

        public void onScanCompleted(String path, Uri uri) {
            try {
                if (uri != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(uri);
                    mActivity.startActivity(intent);
                }
            } catch (ActivityNotFoundException e) {
                Utility.showToast(mActivity, R.string.message_view_display_attachment_toast);
                // TODO: Add a proper warning message (and lots of upstream cleanup to prevent
                // it from happening) in the next release.
            } finally {
                mConnection.disconnect();
                mActivity = null;
            }
        }
    }

    @Override
    public void onMessageNotFound() {
        finish();
    }

    @Override
    public void onMessagesChanged() {
        updateNavigationArrows();
    }
}
