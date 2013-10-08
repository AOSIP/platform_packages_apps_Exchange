/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.exchange.service;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.mail.utils.LogUtils;

public class ContactsSyncAdapterService extends AbstractSyncAdapterService {
    private static final String TAG = Eas.LOG_TAG;
    private static final String ACCOUNT_AND_TYPE_CONTACTS =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_CONTACTS;

    private static final Object sSyncAdapterLock = new Object();
    private static AbstractThreadedSyncAdapter sSyncAdapter = null;

    public ContactsSyncAdapterService() {
        super();
    }

    @Override
    protected AbstractThreadedSyncAdapter getSyncAdapter() {
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new SyncAdapterImpl(this);
            }
            return sSyncAdapter;
        }
    }

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            LogUtils.i(TAG, "onPerformSync Contacts starting %s, %s", account.toString(),
                    extras.toString());
            ContactsSyncAdapterService.performSync(getContext(), account, extras);
            LogUtils.i(TAG, "onPerformSync Contacts ending %s, %s", account.toString(),
                    extras.toString());
        }
    }

    private static boolean hasDirtyRows(ContentResolver resolver, Uri uri, String dirtyColumn) {
        Cursor c = resolver.query(uri, EmailContent.ID_PROJECTION, dirtyColumn + "=1", null, null);
        try {
            return c.getCount() > 0;
        } finally {
            c.close();
        }
    }

    /**
     * Partial integration with system SyncManager; we tell our EAS ExchangeService to start a
     * contacts sync when we get the signal from SyncManager.
     * The missing piece at this point is integration with the push/ping mechanism in EAS; this will
     * be put in place at a later time.
     */
    private static void performSync(Context context, Account account, Bundle extras) {
        ContentResolver cr = context.getContentResolver();
        // If we've been asked to do an upload, make sure we've got work to do
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD)) {
            Uri uri = RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE)
                .build();
            // See if we've got dirty contacts or dirty groups containing our contacts
            boolean changed = hasDirtyRows(cr, uri, RawContacts.DIRTY);
            if (!changed) {
                uri = Groups.CONTENT_URI.buildUpon()
                    .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                    .appendQueryParameter(RawContacts.ACCOUNT_TYPE,
                            Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE)
                    .build();
                changed = hasDirtyRows(cr, uri, Groups.DIRTY);
            }
            if (!changed) {
                LogUtils.i(TAG, "Upload sync; no changes");
                return;
            }
        }

        // Forward the sync request to the EmailSyncAdapterService.
        final Bundle mailExtras = new Bundle(4);
        mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
            mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        }
        final long extrasMailboxId = extras.getLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, 0);
        if (extrasMailboxId != 0) {
            // If we've been given a mailbox, specify a sync for just that mailbox.
            mailExtras.putLong(Mailbox.SYNC_EXTRA_MAILBOX_ID, extrasMailboxId);
        } else {
            // Otherwise, specify a sync for all calendars.
            mailExtras.putInt(Mailbox.SYNC_EXTRA_MAILBOX_TYPE, Mailbox.TYPE_CONTACTS);
        }
        ContentResolver.requestSync(account, EmailContent.AUTHORITY, mailExtras);
        LogUtils.i(TAG, "requestSync ContactsSyncAdapter %s, %s",
                account.toString(), mailExtras.toString());
    }
}
