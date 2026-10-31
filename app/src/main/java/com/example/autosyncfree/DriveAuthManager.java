package com.example.autosyncfree;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

import java.util.Collections;

public class DriveAuthManager {
    private static final String TAG = "DriveAuthManager";
    public interface AuthListener {
        void onAuthSuccess();
        void onAuthFailure(String msg);
    }
    private final Activity activity;
    private final GoogleSignInClient client;
    private final ActivityResultLauncher<Intent> launcher;
    private Drive driveService;
    private AuthListener pendingListener;

    public DriveAuthManager(Activity activity) {
        this.activity = activity;
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
            .requestEmail()
            .build();
        this.client = GoogleSignIn.getClient(activity, gso);
        this.launcher = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                try {
                    Task<GoogleSignInAccount> task =
                        GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    if (account != null) {
                        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                            activity, Collections.singleton(DriveScopes.DRIVE_FILE));
                        credential.setSelectedAccount(account.getAccount());
                        driveService = new Drive.Builder(
                            new NetHttpTransport(),
                            GsonFactory.getDefaultInstance(),
                            credential)
                            .setApplicationName("AutoSync Free")
                            .build();
                        if (pendingListener != null) pendingListener.onAuthSuccess();
                    }
                } catch (ApiException e) {
                    Log.e(TAG, "Sign-in failed", e);
                    if (pendingListener != null) pendingListener.onAuthFailure(
                        "Code " + e.getStatusCode() + ": " + e.getMessage());
                } finally {
                    pendingListener = null;
                }
            });
    }

    public void authorize(AuthListener listener) {
        if (driveService != null) { listener.onAuthSuccess(); return; }
        this.pendingListener = listener;
        launcher.launch(client.getSignInIntent());
    }

    public Drive getDriveService() { return driveService; }
}
