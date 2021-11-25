/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2013-2021 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.widget.Toast;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import java.util.Date;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import ch.threema.app.BuildConfig;
import ch.threema.app.R;
import ch.threema.app.ThreemaApplication;
import ch.threema.app.dialogs.GenericAlertDialog;
import ch.threema.app.dialogs.GenericProgressDialog;
import ch.threema.app.dialogs.NewContactDialog;
import ch.threema.app.exceptions.EntryAlreadyExistsException;
import ch.threema.app.exceptions.FileSystemNotPresentException;
import ch.threema.app.exceptions.InvalidEntryException;
import ch.threema.app.exceptions.PolicyViolationException;
import ch.threema.app.managers.ServiceManager;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.LockAppService;
import ch.threema.app.services.QRCodeService;
import ch.threema.app.utils.AppRestrictionUtil;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DialogUtil;
import ch.threema.app.utils.LogUtil;
import ch.threema.app.utils.QRScannerUtil;
import ch.threema.app.utils.TestUtil;
import ch.threema.localcrypto.MasterKeyLockedException;
import ch.threema.storage.models.ContactModel;

import static ch.threema.domain.protocol.csp.ProtocolDefines.IDENTITY_LEN;

public class AddContactActivity extends ThreemaActivity implements GenericAlertDialog.DialogClickListener, NewContactDialog.NewContactDialogClickListener {
	private static final String DIALOG_TAG_ADD_PROGRESS = "ap";
	private static final String DIALOG_TAG_ADD_ERROR = "ae";
	private static final String DIALOG_TAG_ADD_USER = "au";
	private static final String DIALOG_TAG_ADD_BY_ID = "abi";
	public static final String EXTRA_ADD_BY_ID = "add_by_id";
	public static final String EXTRA_QR_RESULT = "qr_result";

	private static final int PERMISSION_REQUEST_CAMERA = 1;

	private ContactService contactService;
	private QRCodeService qrCodeService;
	private LockAppService lockAppService;
	private ServiceManager serviceManager;
	private AsyncTask<Void, Void, Exception> addContactTask;

	public void onCreate(Bundle savedInstanceState) {
		serviceManager = ThreemaApplication.getServiceManager();

		if (serviceManager == null) {
			finish();
			return;
		}

		try {
			this.qrCodeService = this.serviceManager.getQRCodeService();
			this.contactService = this.serviceManager.getContactService();
			this.lockAppService = this.serviceManager.getLockAppService();
		} catch (MasterKeyLockedException | FileSystemNotPresentException e) {
			LogUtil.exception(e, this);
			finish();
			return;
		}

		if (ConfigUtils.getAppTheme(this) == ConfigUtils.THEME_DARK) {
			setTheme(R.style.Theme_Threema_Translucent_Dark);
		}

		super.onCreate(savedInstanceState);

		supportRequestWindowFeature(Window.FEATURE_NO_TITLE);

		onNewIntent(getIntent());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (intent != null) {
			String action = intent.getAction();

			if (action != null) {
				if (action.equals(Intent.ACTION_VIEW)) {
					Uri dataUri = intent.getData();

					if (TestUtil.required(dataUri)) {
						String scheme = dataUri.getScheme();
						String host = dataUri.getHost();

						if (scheme != null && host != null) {
							if (
									(BuildConfig.uriScheme.equals(scheme) && "add".equals(host))
											||
											("https".equals(scheme) && BuildConfig.actionUrl.equals(host) && "/add".equals(dataUri.getPath()))
							) {

								String id = dataUri.getQueryParameter("id");

								if (TestUtil.required(id)) {
									addContactByIdentity(id);
								}
							}
						}
					}
				}
			}
			if (intent.getStringExtra(EXTRA_QR_RESULT) != null) {
				parseQrResult(intent.getStringExtra(EXTRA_QR_RESULT));
			}

			if (intent.getBooleanExtra(EXTRA_ADD_BY_ID, false)) {
				requestID();
			}
		}
	}

	private void parseQrResult(String payload) {
		// first: try to parse as contact result (contact scan)
		QRCodeService.QRCodeContentResult contactQRCode = this.qrCodeService.getResult(payload);

		if (contactQRCode != null) {
			addContactByQRResult(contactQRCode);
			return;
		}

		// second: try uri scheme
		String scannedIdentity = null;
		Uri uri = Uri.parse(payload);
		if (uri != null) {
			String scheme = uri.getScheme();
			if (BuildConfig.uriScheme.equals(scheme) && "add".equals(uri.getAuthority())) {
				scannedIdentity = uri.getQueryParameter("id");
			} else if ("https".equals(scheme) && BuildConfig.contactActionUrl.equals(uri.getHost())) {
				scannedIdentity = uri.getLastPathSegment();
			}

			if (scannedIdentity != null && scannedIdentity.length() == IDENTITY_LEN) {
				addContactByIdentity(scannedIdentity);
			}
		}
	}

	@SuppressLint("StaticFieldLeak")
	private void addContactByIdentity(final String identity) {
		if (lockAppService.isLocked()) {
			finish();
			return;
		}

		addContactTask = new AsyncTask<Void, Void, Exception>() {
			ContactModel newContactModel;

			@Override
			protected void onPreExecute() {
				GenericProgressDialog.newInstance(R.string.creating_contact, R.string.please_wait).show(getSupportFragmentManager(), DIALOG_TAG_ADD_PROGRESS);
			}

			@Override
			protected Exception doInBackground(Void... params) {
				try {
					newContactModel = contactService.createContactByIdentity(identity, false);
				} catch (Exception e) {
					return e;
				}
				return null;
			}

			@Override
			protected void onPostExecute(Exception exception) {
				if (isDestroyed()) {
					return;
				}

				DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_ADD_PROGRESS, true);

				if (exception == null) {
					newContactAdded(newContactModel);
				} else if (exception instanceof EntryAlreadyExistsException) {
					Toast.makeText(AddContactActivity.this, ((EntryAlreadyExistsException) exception).getTextId(), Toast.LENGTH_SHORT).show();
					showContactDetail(identity);
					finish();
				} else if (exception instanceof InvalidEntryException){
					GenericAlertDialog.newInstance(R.string.title_adduser, ((InvalidEntryException) exception).getTextId(), R.string.close, 0).show(getSupportFragmentManager(), DIALOG_TAG_ADD_ERROR);
				} else if (exception instanceof PolicyViolationException) {
					Toast.makeText(AddContactActivity.this, R.string.disabled_by_policy_short, Toast.LENGTH_SHORT).show();
					finish();
				}
			}
		};
		addContactTask.execute();
	}

	@Override
	protected void onDestroy() {
		if (addContactTask != null) {
			addContactTask.cancel(true);
		}

		super.onDestroy();
	}

	@SuppressLint("StaticFieldLeak")
	private void addContactByQRResult(final QRCodeService.QRCodeContentResult qrResult) {
		if (qrResult.getExpirationDate() != null
			&& qrResult.getExpirationDate().before(new Date())) {
			GenericAlertDialog.newInstance(R.string.title_adduser, getString(R.string.expired_barcode), R.string.ok, 0).show(getSupportFragmentManager(), "ex");
			return;
		}

		ContactModel contactModel = contactService.getByPublicKey(qrResult.getPublicKey());

		if (contactModel != null) {
			// contact already exists - update it
			boolean c = true;

			int contactVerification = this.contactService.updateContactVerification(contactModel.getIdentity(), qrResult.getPublicKey());
			int textResId;
			switch (contactVerification) {
				case ContactService.ContactVerificationResult_ALREADY_VERIFIED:
					textResId = R.string.scan_duplicate;
					break;
				case ContactService.ContactVerificationResult_VERIFIED:
					textResId = R.string.scan_successful;
					break;
				default:
					textResId = R.string.id_mismatch;
					c = false;
			}

			if(!c) {
				GenericAlertDialog.newInstance(R.string.title_adduser, getString(textResId), R.string.ok, 0).show(getSupportFragmentManager(), DIALOG_TAG_ADD_USER);
			}
			else {
				if (contactService.getIsHidden(contactModel.getIdentity())) {
					contactService.setIsHidden(contactModel.getIdentity(), false);
					newContactAdded(contactModel);
				} else {
					Toast.makeText(this.getApplicationContext(), textResId, Toast.LENGTH_SHORT).show();
					showContactDetail(contactModel.getIdentity());
					this.finish();
				}
			}
		} else {
			if (AppRestrictionUtil.isAddContactDisabled(this)) {
				return;
			}

			// add new contact
			new AsyncTask<Void, Void, String>() {
				ContactModel newContactModel;

				@Override
				protected void onPreExecute() {
					GenericProgressDialog.newInstance(R.string.creating_contact, R.string.please_wait).show(getSupportFragmentManager(), DIALOG_TAG_ADD_PROGRESS);
				}

				@Override
				protected String doInBackground(Void... params) {
					try {
						newContactModel = contactService.createContactByQRResult(qrResult);
					} catch (final InvalidEntryException e) {
						return getString(e.getTextId());
					} catch (final EntryAlreadyExistsException e) {
						return getString(e.getTextId());
					} catch (final PolicyViolationException e) {
						return getString(R.string.disabled_by_policy_short);
					}
					return null;
				}

				@Override
				protected void onPostExecute(String message) {
					DialogUtil.dismissDialog(getSupportFragmentManager(), DIALOG_TAG_ADD_PROGRESS, true);

					if (TestUtil.empty(message)) {
						newContactAdded(newContactModel);
					} else {
						GenericAlertDialog.newInstance(R.string.title_adduser, message, R.string.ok, 0).show(getSupportFragmentManager(), DIALOG_TAG_ADD_USER);
					}
				}
			}.execute();
		}
	}

	private void showContactDetail(String id) {
		Intent intent = new Intent(this, ContactDetailActivity.class);
		intent.putExtra(ThreemaApplication.INTENT_DATA_CONTACT, id);
		this.startActivity(intent);
		finish();
	}

	private void newContactAdded(ContactModel contactModel) {
		if(contactModel != null) {
			Toast.makeText(this.getApplicationContext(), R.string.creating_contact_successful, Toast.LENGTH_SHORT).show();
 			showContactDetail(contactModel.getIdentity());
			finish();
		}
	}

	private void scanQR() {
		if (ConfigUtils.requestCameraPermissions(this, null, PERMISSION_REQUEST_CAMERA)) {
			QRScannerUtil.getInstance().initiateGeneralThreemaQrScanner(this, getString(R.string.qr_scanner_id_hint));
		}
	}

	private void requestID() {
		DialogFragment dialogFragment = NewContactDialog.newInstance(
			R.string.menu_add_contact,
			R.string.enter_id_hint,
			R.string.ok,
			R.string.cancel);
		dialogFragment.show(getSupportFragmentManager(), DIALOG_TAG_ADD_BY_ID);
	}

	@Override
	public void onYes(String tag, Object data) {
		finish();
	}

	@Override
	public void onNo(String tag, Object data) {
		finish();
	}

	@TargetApi(Build.VERSION_CODES.M)
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		switch (requestCode) {
			case PERMISSION_REQUEST_CAMERA:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					scanQR();
				} else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
					ConfigUtils.showPermissionRationale(this, getWindow().getDecorView().findViewById(android.R.id.content), R.string.permission_camera_qr_required, new BaseTransientBottomBar.BaseCallback<Snackbar>() {
						@Override
						public void onDismissed(Snackbar transientBottomBar, int event) {
							super.onDismissed(transientBottomBar, event);
							finish();
						}
					});
				} else {
					finish();
				}
				break;
			default:
				break;
		}
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
	}

	@Override
	public void onContactEnter(String tag, String text) {
		if (TestUtil.required(text)) {
			addContactByIdentity(text);
		}
	}

	@Override
	public void onCancel(String tag) {
		finish();
	}

	@Override
	public void onScanButtonClick(String tag) {
		scanQR();
	}
}
