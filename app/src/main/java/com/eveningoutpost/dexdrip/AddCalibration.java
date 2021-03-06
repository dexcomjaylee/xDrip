package com.eveningoutpost.dexdrip;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.eveningoutpost.dexdrip.Models.Calibration;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.Models.UserError.Log;
import com.eveningoutpost.dexdrip.UtilityModels.CollectionServiceStarter;
import com.eveningoutpost.dexdrip.UtilityModels.UndoRedo;
import com.eveningoutpost.dexdrip.wearintegration.WatchUpdaterService;
import static com.eveningoutpost.dexdrip.Home.startWatchUpdaterService;

import java.util.UUID;

public class AddCalibration extends AppCompatActivity implements NavigationDrawerFragment.NavigationDrawerCallbacks {
    Button button;
    //public static String menu_name = "Add Calibration";
    private static final String TAG = "AddCalibration";
    private NavigationDrawerFragment mNavigationDrawerFragment;
    private static double lastExternalCalibrationValue = 0;
    public static final long estimatedInterstitialLagSeconds = 600; // how far behind venous glucose do we estimate

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (CollectionServiceStarter.isBTShare(getApplicationContext())) {
            Intent intent = new Intent(this, Home.class);
            startActivity(intent);
            finish();
        }
        setContentView(R.layout.activity_add_calibration);
        addListenerOnButton();
        automatedCalibration();
    }

    protected void onResume() {
        super.onResume();
        mNavigationDrawerFragment = (NavigationDrawerFragment) getFragmentManager().findFragmentById(R.id.navigation_drawer);
        mNavigationDrawerFragment.setUp(R.id.navigation_drawer, (DrawerLayout) findViewById(R.id.drawer_layout), getString(R.string.add_calibration), this);
        automatedCalibration();
    }

    @Override
    public void onNavigationDrawerItemSelected(int position) {
        mNavigationDrawerFragment.swapContext(position);
    }

    // jamorham - receive automated calibration via broadcast intent / tasker receiver
    public synchronized void automatedCalibration() {

        final PowerManager.WakeLock wl = JoH.getWakeLock("xdrip-autocalib", 60000);

        Log.d(TAG, "Auto calibration...");
        final Bundle extras = getIntent().getExtras();
        if (extras != null) {
            final String string_value = extras.getString("bg_string");
            final String bg_age = extras.getString("bg_age");
            final String from_external = extras.getString("from_external", "false");
            final String from_interactive = extras.getString("from_interactive", "false");
            final String note_only = extras.getString("note_only", "false");
            final String allow_undo = extras.getString("allow_undo", "false");

            if ((Sensor.isActive() || Home.get_follower())) {

                if (!TextUtils.isEmpty(string_value)) {
                    if (!TextUtils.isEmpty(bg_age)) {
                        final double calValue = Double.parseDouble(string_value);
                        new Thread() {
                            @Override
                            public void run() {

                                final PowerManager.WakeLock wlt = JoH.getWakeLock("xdrip-autocalibt", 60000);

                                long bgAgeNumber = Long.parseLong(bg_age);

                                if ((bgAgeNumber >= 0) && (bgAgeNumber < 86400)) {
                                    long localEstimatedInterstitialLagSeconds = 0;

                                    // most appropriate raw value to calculate calibration
                                    // from should be some time after venous glucose reading
                                    // adjust timestamp for this if we can
                                    if (bgAgeNumber > estimatedInterstitialLagSeconds) {
                                        localEstimatedInterstitialLagSeconds = estimatedInterstitialLagSeconds;
                                    }
                                    // Sanity checking can go here

                                    if (calValue > 0) {
                                        if (calValue != lastExternalCalibrationValue) {

                                            if (!Home.get_follower()) {
                                                lastExternalCalibrationValue = calValue;
                                                Calibration calibration = Calibration.create(calValue, bgAgeNumber, getApplicationContext(), (note_only.equals("true")), localEstimatedInterstitialLagSeconds);
                                                if ((calibration != null) && allow_undo.equals("true")) {
                                                    UndoRedo.addUndoCalibration(calibration.uuid);
                                                }
                                                startWatchUpdaterService(getApplicationContext(), WatchUpdaterService.ACTION_SYNC_CALIBRATION, TAG);
                                            } else {
                                                // follower sends the calibration data onwards only if sourced from interactive request
                                                if (from_interactive.equals("true")) {
                                                    Log.d(TAG, "Interactive calibration and we are follower so sending to master");
                                                    sendFollowerCalibration(calValue, bgAgeNumber);
                                                } else {
                                                    Log.d(TAG, "Not an interactive calibration so not sending to master");
                                                }
                                            }

                                        } else {
                                            Log.w(TAG, "Ignoring Remote calibration value as identical to last one: " + calValue);
                                        }

                                        if (from_external.equals("true")) {
                                            Log.d("jamorham calib", "Relaying tasker pushed calibration");
                                            GcmActivity.pushCalibration(string_value, bg_age);
                                        }
                                    }
                                } else {
                                    Log.wtf("CALERROR", "bg age either in future or older than 1 day: " + bgAgeNumber);
                                }

                                JoH.releaseWakeLock(wlt);
                            }
                        }.start();

                    } else {
                        Log.w("CALERROR", "ERROR during automated calibration - no valid bg age");
                    }
                } else {
                    Log.w("CALERROR", "ERROR during automated calibration - no valid value");
                }
            } else {
                Log.w("CALERROR", "ERROR during automated calibration - no active sensor");
            }
            JoH.releaseWakeLock(wl);
            finish();
        }


    }


    public void addListenerOnButton() {

        button = (Button) findViewById(R.id.save_calibration_button);

        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(final View v) {

                if ((Sensor.isActive() || Home.get_follower())) {
                    final EditText value = (EditText) findViewById(R.id.bg_value);
                    final String string_value = value.getText().toString();
                    if (!TextUtils.isEmpty(string_value)) {

                        try {
                            final double calValue = JoH.tolerantParseDouble(string_value);

                            if (!Home.get_follower()) {
                                Calibration calibration = Calibration.create(calValue, getApplicationContext());
                                if (calibration != null) {
                                    UndoRedo.addUndoCalibration(calibration.uuid);
                                    startWatchUpdaterService(v.getContext(), WatchUpdaterService.ACTION_SYNC_CALIBRATION, TAG);

                                } else {
                                    Log.e(TAG, "Calibration creation resulted in null");
                                    JoH.static_toast_long("Could not create calibration!");
                                    // TODO probably follower must ensure it has a valid sensor regardless..
                                }
                            } else if (Home.get_follower()) {
                                // Sending the data for the master to update the main tables.
                                sendFollowerCalibration(calValue, 0); // default offset is 0
                            }
                            Intent tableIntent = new Intent(v.getContext(), Home.class);
                            startActivity(tableIntent);

                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Number format exception ", e);
                            Home.toaststatic("Got error parsing number in calibration");
                        }
                        // }
                        // }.start();
                        finish();
                    } else {
                        value.setError("Calibration Can Not be blank");
                    }
                } else {
                    Log.w("CALERROR", "Sensor is not active, cannot calibrate");
                }
            }
        });

    }

    // helper function for sending calibrations to master when we are follower
    public static void sendFollowerCalibration(double calValue, long offset) {
        Log.d(TAG, "sendFollowerCalibration: " + calValue + " " + offset);
        final String uuid = UUID.randomUUID().toString();
        GcmActivity.pushCalibration2(calValue, uuid, offset);
        UndoRedo.addUndoCalibration(uuid);
        JoH.static_toast_long("Calibration sent to master for processing");
    }
}
