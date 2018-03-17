package io.gonative.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.onesignal.OneSignal;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import io.gonative.android.library.AppConfig;


public class SubscriptionsActivity extends AppCompatActivity {
    private static final String TAG = SubscriptionsActivity.class.getName();
    private static final String sharedPrefPrefix = "oneSignalTag:";
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subscriptions);
        progressBar = (ProgressBar) findViewById(R.id.progress);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        try {
            String tagsJsonUrl = AppConfig.getInstance(this).oneSignalTagsJsonUrl;
            if (tagsJsonUrl == null || tagsJsonUrl.isEmpty()) {
                handleError("Error retrieving tag list", null);
            }

            URL url = new URL(tagsJsonUrl);

            loadManifest(url);
        } catch (Exception e) {
            handleError("Error retrieving tag list", e);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id==android.R.id.home) {
            finish();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void handleError(final String userMessage, Exception e) {
        if (e != null) {
            Log.e(TAG, e.getMessage(), e);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(SubscriptionsActivity.this, userMessage, Toast.LENGTH_LONG).show();
                finish();
            }

        });
    }

    private void loadManifest(final URL url) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.connect();

                    if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        throw new Error("Got status " + conn.getResponseCode() + " when downloading " + url.toString());
                    }

                    ByteArrayOutputStream baos;
                    if (conn.getContentLength() > 0)
                        baos = new ByteArrayOutputStream(conn.getContentLength());
                    else baos = new ByteArrayOutputStream();

                    InputStream is = new BufferedInputStream(conn.getInputStream());
                    IOUtils.copy(is, baos);
                    is.close();
                    baos.close();

                    String json = baos.toString();
                    final SubscriptionsModel model = SubscriptionsModel.fromJSONString(json);

                    if (model == null) {
                        throw new Exception("Error parsing JSON from " + url.toString());
                    }

                    // update OneSignal info
                    OneSignal.getTags(new OneSignal.GetTagsHandler() {
                        @Override
                        public void tagsAvailable(JSONObject tags) {
                            if (tags != null) {
                                for (SubscriptionsModel.SubscriptionsSection section : model.sections) {
                                    for (SubscriptionsModel.SubscriptionItem item : section.items) {
                                        if (item.identifier != null && tags.has(item.identifier)) {
                                            item.isSubscribed = true;
                                        }
                                    }
                                }
                            }

                            SubscriptionsActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progressBar.setVisibility(View.GONE);

                                    SubscriptionsFragment subscriptionsFragment = new SubscriptionsFragment();
                                    subscriptionsFragment.setModel(model);
                                    getFragmentManager().beginTransaction()
                                            .replace(R.id.subscriptions_fragment, subscriptionsFragment)
                                            .commit();
                                }
                            });
                        }
                    });

                } catch (Exception e) {
                    handleError("Error retrieving tag list", e);
                }

                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public static class SubscriptionsFragment extends PreferenceFragment {
        private SubscriptionsModel model;

        public void setModel(SubscriptionsModel model) {
            this.model = model;
        }

        @Override
        public void onCreate(Bundle savedInstance) {
            super.onCreate(savedInstance);

            Context context = getActivity();

            // clear all preferences we created, or else the values saved on the device override
            // what we have just retrieved from OneSignal
            SharedPreferences sharedPreferences = getPreferenceManager().getDefaultSharedPreferences(context);
            SharedPreferences.Editor edit = null;
            for (String key : sharedPreferences.getAll().keySet()) {
                if (key.startsWith(sharedPrefPrefix)) {
                    if (edit == null) {
                        edit = sharedPreferences.edit();
                    }

                    edit.remove(key);
                }
            }
            if (edit != null) {
                edit.commit();
            }

            // create the preference tree
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

            if (this.model != null) {
                for (SubscriptionsModel.SubscriptionsSection section : this.model.sections) {
                    PreferenceCategory category = new PreferenceCategory(context);
                    category.setTitle(section.name);
                    screen.addPreference(category);

                    for (SubscriptionsModel.SubscriptionItem item : section.items) {
                        final String identifier = item.identifier;
                        CheckBoxPreference preference = new CheckBoxPreference(context);
                        preference.setTitle(item.name);
                        preference.setChecked(item.isSubscribed);
                        preference.setKey(sharedPrefPrefix + identifier);
                        preference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                            @Override
                            public boolean onPreferenceChange(Preference preference, Object o) {
                                if (o instanceof Boolean && ((Boolean)o).booleanValue()) {
                                    OneSignal.sendTag(identifier, "1");
                                } else {
                                    OneSignal.deleteTag(identifier);
                                }

                                return true;
                            }
                        });

                        category.addPreference(preference);
                    }
                }
            }

            setPreferenceScreen(screen);
        }
    }
}