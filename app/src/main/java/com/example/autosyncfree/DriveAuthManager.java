package com.example.autosyncfree;

import android.content.Intent;
import android.util.Log;

import androidx.activity.ComponentActivity;
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
    private final ComponentActivity activity;
    private final GoogleSignInClient client;
    private final ActivityResultLauncher<Intent> launcher;
    private Drive driveService;
    private AuthListener pendingListener;
    private static final Scope DRIVE_SCOPE = new Scope(DriveScopes.DRIVE);

    public DriveAuthManager(ComponentActivity activity) {
        this.activity = activity;
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(DRIVE_SCOPE)
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
                        buildDriveService(account);
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
        restoreSession();
    }

    private void restoreSession() {
        try {
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
            if (account != null && GoogleSignIn.hasPermissions(account, DRIVE_SCOPE)) {
                buildDriveService(account);
                Log.i(TAG, "Session restored for " + account.getEmail());
            }
        } catch (Exception e) {
            Log.w(TAG, "restoreSession failed", e);
        }
    }

    private void buildDriveService(GoogleSignInAccount account) {
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
            activity, Collections.singleton(DriveScopes.DRIVE));
        credential.setSelectedAccount(account.getAccount());
        driveService = new Drive.Builder(
            new NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential)
            .setApplicationName("FloGPT AutoSync")
            .build();
    }

    public void authorize(AuthListener listener) {
        this.pendingListener = listener;
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(activity);
        if (account != null && GoogleSignIn.hasPermissions(account, DRIVE_SCOPE)) {
            if (driveService == null) buildDriveService(account);
            listener.onAuthSuccess();
            return;
        }
        if (account != null) {
            client.signOut().addOnCompleteListener(t -> launcher.launch(client.getSignInIntent()));
        } else {
            launcher.launch(client.getSignInIntent());
        }
    }

    public boolean isConnected() { return driveService != null; }
    public Drive getDriveService() { return driveService; }
}
