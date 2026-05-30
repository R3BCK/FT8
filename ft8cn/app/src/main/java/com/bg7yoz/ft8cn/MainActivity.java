package com.bg7yoz.ft8cn;
/**
 * Main Activity of FT8CN application.
 * This APP uses Fragment framework, each Fragment implements different functionality.
 * ----2022.5.6-----
 * Main functions:
 * 1. Create MainViewModel instance. MainViewModel lives for entire app lifecycle,
 *    handles recording, decoding, etc.
 * 2. Request permissions for recording and storage.
 * 3. Implement Fragment navigation management.
 * 4. Show notification after USB serial connection.
 *
 * @author BG7YOZ
 * @date 2022.5.6
 */


import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.bg7yoz.ft8cn.bluetooth.BluetoothStateBroadcastReceive;
import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.connector.CableSerialPort;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.database.OnAfterQueryConfig;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.databinding.MainActivityBinding;
import com.bg7yoz.ft8cn.floatview.FloatView;
import com.bg7yoz.ft8cn.floatview.FloatViewButton;
import com.bg7yoz.ft8cn.grid_tracker.GridTrackerMainActivity;
import com.bg7yoz.ft8cn.log.ImportSharedLogs;
import com.bg7yoz.ft8cn.log.OnShareLogEvents;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.FreqDialog;
import com.bg7yoz.ft8cn.ui.ScanFragment;
import com.bg7yoz.ft8cn.ui.SetVolumeDialog;
import com.bg7yoz.ft8cn.ui.ShareLogsProgressDialog;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class MainActivity extends AppCompatActivity {
    private BluetoothStateBroadcastReceive mReceive;
    private static final String TAG = "MainActivity";
    private MainViewModel mainViewModel;
    private NavController navController;
    private static boolean animatorRunned = false;

    private MainActivityBinding binding;
    private FloatView floatView;

    private ShareLogsProgressDialog dialog = null;

    String[] permissions = new String[]{Manifest.permission.RECORD_AUDIO
            , Manifest.permission.ACCESS_COARSE_LOCATION
            , Manifest.permission.ACCESS_WIFI_STATE
            , Manifest.permission.BLUETOOTH
            , Manifest.permission.BLUETOOTH_ADMIN
            , Manifest.permission.MODIFY_AUDIO_SETTINGS
            , Manifest.permission.WAKE_LOCK
            , Manifest.permission.ACCESS_FINE_LOCATION};
    List<String> mPermissionList = new ArrayList<>();

    private static final int PERMISSION_REQUEST = 1;

    // === USB Reconnect and RFI protection ===
    private static final String PREFS_USB = "FT8CN_UsbPrefs";
    private static final String KEY_DISCONNECT_COUNT = "disconnect_count_";
    private static final String KEY_LAST_TX_FREQ = "last_tx_frequency";
    private static final int MAX_DISCONNECT_BEFORE_STOP = 3;
    // =========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{Manifest.permission.RECORD_AUDIO
                    , Manifest.permission.ACCESS_COARSE_LOCATION
                    , Manifest.permission.ACCESS_WIFI_STATE
                    , Manifest.permission.BLUETOOTH
                    , Manifest.permission.BLUETOOTH_ADMIN
                    , Manifest.permission.BLUETOOTH_CONNECT
                    , Manifest.permission.MODIFY_AUDIO_SETTINGS
                    , Manifest.permission.WAKE_LOCK
                    , Manifest.permission.ACCESS_FINE_LOCATION};
        }
        // Запрос разрешений при старте
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        checkPermission();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                , WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                , WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);

        // [TEMP] Force start foreground service for testing
        com.bg7yoz.ft8cn.service.RecordingForegroundService.start(this);
        Log.d("MainActivity", "Foreground service started from MainActivity");

        GeneralVariables.getInstance().setMainContext(getApplicationContext());

        GeneralVariables.isTraditionalChinese =
                getResources().getConfiguration().locale.getDisplayCountry().equals("China");

        GeneralVariables.isChina = (getResources().getConfiguration().locale
                .getLanguage().toUpperCase().startsWith("ZH"));

        mainViewModel = MainViewModel.getInstance(this);
        binding = MainActivityBinding.inflate(getLayoutInflater());
        binding.initDataLayout.setVisibility(View.VISIBLE);
        setContentView(binding.getRoot());


        ToastMessage.getInstance();
        registerBluetoothReceiver();
        if (mainViewModel.isBTConnected()) {
            mainViewModel.setBlueToothOn();
        }


        GeneralVariables.mutableDebugMessage.observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s.length() > 1) {
                    binding.debugLayout.setVisibility(View.VISIBLE);
                } else {
                    binding.debugLayout.setVisibility(View.GONE);
                }
                binding.debugMessageTextView.setText(s);
            }
        });
        binding.debugLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.debugLayout.setVisibility(View.GONE);
            }
        });


        mainViewModel.mutableIsRecording.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.utcProgressBar.setVisibility(View.VISIBLE);
                } else {
                    binding.utcProgressBar.setVisibility(View.GONE);
                }
            }
        });
        mainViewModel.timerSec.observe(this, new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                if (mainViewModel.ft8TransmitSignal.sequential == UtcTimer.getNowSequential()
                        && mainViewModel.ft8TransmitSignal.isActivated()) {
                    binding.utcProgressBar.setBackgroundColor(getColor(R.color.calling_list_isMyCall_color));
                } else {
                    binding.utcProgressBar.setBackgroundColor(getColor(R.color.progresss_bar_back_color));
                }
                binding.utcProgressBar.setProgress((int) ((aLong / 1000) % 15));
            }
        });

        binding.transmittingLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.transmittingLayout.setVisibility(View.GONE);
            }
        });

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;
        navController = navHostFragment.getNavController();

        NavigationUI.setupWithNavController(binding.navView, navController);
        binding.navView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                // [NEW] Обработка перехода на ScanFragment
                if (item.getItemId() == R.id.menu_nav_scan) {
                    navController.navigate(R.id.menu_nav_scan);
                    return true;
                }
                navController.navigate(item.getItemId());
                return true;
            }
        });

        binding.welcomTextView.setText(String.format(getString(R.string.version_info)
                , GeneralVariables.VERSION, GeneralVariables.BUILD_DATE));

        floatView = new FloatView(this, 32);
        if (!animatorRunned) {
            animationImage();
            animatorRunned = true;
        } else {
            binding.initDataLayout.setVisibility(View.GONE);
            InitFloatView();
        }
        InitData();


        mainViewModel.mutableIsFlexRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    floatView.addButton(R.id.flex_radio, "flex_radio", R.drawable.flex_icon
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.flexRadioInfoFragment);
                                }
                            });
                } else {
                    floatView.deleteButtonByName("flex_radio");
                }
            }
        });

        mainViewModel.mutableIsXieguRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    floatView.addButton(R.id.xiegu_radio, "xiegu_radio", R.drawable.xiegulogo32
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.xieguInfoFragment);
                                }
                            });
                } else {
                    floatView.deleteButtonByName("xiegu_radio");
                }
            }
        });

        binding.closeSelectSerialPortImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.selectSerialPortLayout.setVisibility(View.GONE);
            }
        });

        mainViewModel.mutableSerialPorts.observe(this, new Observer<ArrayList<CableSerialPort.SerialPort>>() {
            @Override
            public void onChanged(ArrayList<CableSerialPort.SerialPort> serialPorts) {
                setSelectUsbDevice();
            }
        });

        mainViewModel.getUsbDevice();


        binding.transmittingMessageTextView.setAnimation(AnimationUtils.loadAnimation(this
                , R.anim.view_blink));
        mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observe(this,
                new Observer<Boolean>() {
                    @Override
                    public void onChanged(Boolean aBoolean) {
                        if (aBoolean) {
                            binding.transmittingLayout.setVisibility(View.VISIBLE);
                        } else {
                            binding.transmittingLayout.setVisibility(View.GONE);
                        }
                    }
                });

        mainViewModel.ft8TransmitSignal.mutableTransmittingMessage.observe(this,
                new Observer<String>() {
                    @Override
                    public void onChanged(String s) {
                        binding.transmittingMessageTextView.setText(s);
                    }
                });

        if (Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue())) {
            showShareDialog();
        }else {
            doReceiveShareFile(getIntent());
        }

        requestIgnoreBatteryOptimization();

    }


    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                String packageName = getPackageName();
                boolean isIgnoring = pm.isIgnoringBatteryOptimizations(packageName);

                if (!isIgnoring) {
                    Log.d(TAG, "Battery optimization not ignored, requesting exemption");

                    new AlertDialog.Builder(this)
                            .setTitle("Background Operation")
                            .setMessage("To ensure stable FT8 transmission and reception, FT8CN needs to run in the background. Please allow the app to ignore battery optimization.\n\nThis prevents Android from stopping audio recording and radio communication when the screen is off.")
                            .setPositiveButton("Allow", (dialog, which) -> {
                                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                intent.setData(Uri.parse("package:" + packageName));
                                startActivity(intent);
                            })
                            .setNegativeButton("Later", (dialog, which) -> {
                                Log.i(TAG, "User deferred battery optimization exemption");
                                dialog.dismiss();
                            })
                            .setCancelable(false)
                            .show();
                } else {
                    Log.d(TAG, "Battery optimization already ignored for " + packageName);
                }
            }
        }
    }


    private void doReceiveShareFile(Intent intent) {
        Uri uri = (Uri) intent.getData();

        if (uri != null) {
            ImportSharedLogs importSharedLogs = null;
            showShareDialog();
            try {

                importSharedLogs = new ImportSharedLogs(mainViewModel);
                Log.e(TAG,"Starting import...");
                mainViewModel.mutableImportShareRunning.setValue(true);
                importSharedLogs.doImport(getBaseContext().getContentResolver().openInputStream(uri)
                        ,new OnShareLogEvents() {
                            @Override
                            public void onPreparing(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }

                            @Override
                            public void onShareStart(int count, String info) {
                                mainViewModel.mutableSharePosition.postValue(0);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableImportShareRunning.postValue(true);
                                mainViewModel.mutableShareCount.postValue(count);
                            }

                            @Override
                            public boolean onShareProgress(int count, int position, String info) {
                                mainViewModel.mutableSharePosition.postValue(position);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareCount.postValue(count);
                                return Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue());
                            }

                            @Override
                            public void afterGet(int count, String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableImportShareRunning.postValue(false);
                            }

                            @Override
                            public void onShareFailed(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }
                        });
            } catch (IOException e) {
                mainViewModel.mutableImportShareRunning.postValue(false);
                Log.e(TAG,String.format("Error: %s",e.getMessage()));
                ToastMessage.show(e.getMessage());
            }
        } else {
            Log.e(TAG, "File not found when reading file type.");
        }
    }


    private void InitFloatView() {
        binding.container.addView(floatView);
        floatView.setButtonMargin(0);
        floatView.setFloatBoard(FloatView.FLOAT_BOARD.RIGHT);
        floatView.setButtonBackgroundResourceId(R.drawable.float_button_style);

        // === ИСХОДНЫЕ КНОПКИ (рабочая конфигурация) ===

        /*// 1. Fullscreen toggle (верхняя кнопка)
        floatView.addButton(R.id.float_nav, "float_nav", R.drawable.ic_baseline_fullscreen_24,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        FloatViewButton button = floatView.getButtonByName("float_nav");
                        if (binding.navView.getVisibility() == View.VISIBLE) {
                            binding.navView.setVisibility(View.GONE);
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_exit_24);
                            }
                        } else {
                            binding.navView.setVisibility(View.VISIBLE);
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_24);
                            }
                        }
                    }
                });*/
        floatView.addButton(R.id.float_nav, "float_settings", R.drawable.ic_baseline_settings_24,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        // Создаём Intent для текущей MainActivity с флагом открытия настроек
                        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
                        intent.putExtra("OPEN_CONFIG", true);
                        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                    }
                });

        // 2. Frequency dialog
        floatView.addButton(R.id.float_freq, "float_freq", R.drawable.ic_baseline_freq_24,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new FreqDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });

        // 3. Volume control
        floatView.addButton(R.id.set_volume, "set_volume", R.drawable.ic_baseline_volume_up_24,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new SetVolumeDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });

        // 4. Grid Tracker
        floatView.addButton(R.id.grid_tracker, "grid_tracker", R.drawable.ic_baseline_grid_tracker_24,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent(getApplicationContext(), GridTrackerMainActivity.class);
                        startActivity(intent);
                    }
                });

        floatView.initLocation();
    }

    private void InitData() {
        if (mainViewModel.configIsLoaded) return;

        if (mainViewModel.operationBand == null) {
            mainViewModel.operationBand = OperationBand.getInstance(getBaseContext());
        }

        mainViewModel.databaseOpr.getQslDxccToMap();

        mainViewModel.databaseOpr.getAllConfigParameter(new OnAfterQueryConfig() {
            @Override
            public void doOnBeforeQueryConfig(String KeyName) {

            }

            @Override
            public void doOnAfterQueryConfig(String KeyName, String Value) {
                mainViewModel.configIsLoaded = true;
                String grid = MaidenheadGrid.getMyMaidenheadGrid(getApplicationContext());
                if (!grid.equals("")) {
                    GeneralVariables.setMyMaidenheadGrid(grid);
                    mainViewModel.databaseOpr.writeConfig("grid", grid, null);
                }

                mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay);
                if (GeneralVariables.getMyMaidenheadGrid().equals("")
                        || GeneralVariables.myCallsign.equals("")) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            navController.navigate(R.id.menu_nav_config);
                        }
                    });
                }
            }
        });

        new DatabaseOpr.GetCallsignMapGrid(mainViewModel.databaseOpr.getDb()).execute();

        mainViewModel.getFollowCallsignsFromDataBase();
        if (GeneralVariables.callsignDatabase == null) {
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(getBaseContext(), null, 1);
        }
    }


    private void showShareDialog() {
        dialog = new ShareLogsProgressDialog(
                binding.getRoot().getContext()
                , mainViewModel,true);

        dialog.show();
        mainViewModel.mutableSharePosition.postValue(0);
        mainViewModel.mutableShareInfo.postValue("");
        mainViewModel.mutableShareCount.postValue(0);
    }


    private void checkPermission() {
        mPermissionList.clear();

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                mPermissionList.add(permission);
            }
        }

        if (!mPermissionList.isEmpty()) {
            String[] permissions = mPermissionList.toArray(new String[mPermissionList.size()]);
            ActivityCompat.requestPermissions(MainActivity.this, permissions, PERMISSION_REQUEST);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }


    private static final String PREFS_SERIAL = "FT8CN_SerialPrefs";
    private static final String KEY_LAST_PORT_ID = "last_port_id";

    public void setSelectUsbDevice() {
        ArrayList<CableSerialPort.SerialPort> ports = mainViewModel.mutableSerialPorts.getValue();
        binding.selectSerialPortLinearLayout.removeAllViews();

        if (ports == null || ports.isEmpty() || mainViewModel.isRigConnected()) {
            binding.selectSerialPortLayout.setVisibility(View.GONE);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_SERIAL, Context.MODE_PRIVATE);
        String savedPortId = prefs.getString(KEY_LAST_PORT_ID, null);
        CableSerialPort.SerialPort targetPort = null;

        if (ports.size() == 1) {
            targetPort = ports.get(0);
        }
        else if (savedPortId != null) {
            for (CableSerialPort.SerialPort p : ports) {
                if (p.information() != null && p.information().equals(savedPortId)) {
                    targetPort = p;
                    break;
                }
            }
        }

        if (targetPort != null) {
            Log.d(TAG, "Auto-connecting to serial port: " + targetPort.information());

            // Check if we should stop transmission due to repeated disconnects
            if (shouldStopTransmissionOnThisFrequency()) {
                Log.w(TAG, "Transmission stopped: too many USB disconnects on this frequency");
                ToastMessage.show("TX stopped: USB unstable on this band");
                if (mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(false);
                    mainViewModel.ft8TransmitSignal.setTransmitting(false);
                    if (mainViewModel.baseRig != null && mainViewModel.baseRig.isConnected()) {
                        try {
                            mainViewModel.baseRig.setPTT(false);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to send PTT OFF: " + e.getMessage());
                        }
                    }
                }
                binding.selectSerialPortLayout.setVisibility(View.GONE);
                return;
            }

            // Check if rig was transmitting before disconnect - force stop after reconnect
            boolean wasTransmitting = mainViewModel.ft8TransmitSignal.isTransmitting();

            mainViewModel.connectCableRig(this, targetPort);
            binding.selectSerialPortLayout.setVisibility(View.GONE);

            if (savedPortId == null) {
                prefs.edit().putString(KEY_LAST_PORT_ID, targetPort.information()).apply();
            }

            // If was transmitting, ensure TX is stopped after reconnect
            if (wasTransmitting) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (mainViewModel.baseRig != null && mainViewModel.baseRig.isConnected()) {
                        Log.d(TAG, "Forcing PTT OFF after USB reconnect (was transmitting)");
                        try {
                            mainViewModel.baseRig.setPTT(false);
                            mainViewModel.ft8TransmitSignal.setTransmitting(false);
                            mainViewModel.mutableIsRecording.postValue(true);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to force PTT OFF: " + e.getMessage());
                        }
                    }
                }, 500);
            }
        } else {
            for (int i = 0; i < ports.size(); i++) {
                CableSerialPort.SerialPort port = ports.get(i);
                View layout = LayoutInflater.from(this)
                        .inflate(R.layout.select_serial_port_list_view_item, null);
                layout.setId(i);
                TextView textView = layout.findViewById(R.id.selectSerialPortListViewItemTextView);
                textView.setText(port.information());
                binding.selectSerialPortLinearLayout.addView(layout);

                layout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        prefs.edit().putString(KEY_LAST_PORT_ID, port.information()).apply();
                        mainViewModel.connectCableRig(getApplicationContext(), port);
                        binding.selectSerialPortLayout.setVisibility(View.GONE);
                    }
                });
            }
            binding.selectSerialPortLayout.setVisibility(View.VISIBLE);
        }
    }


    /**
     * Check if transmission should be stopped on current frequency due to repeated USB disconnects
     * @return true if transmission should be stopped
     */
    private boolean shouldStopTransmissionOnThisFrequency() {
        SharedPreferences prefs = getSharedPreferences(PREFS_USB, Context.MODE_PRIVATE);
        long currentFreq = GeneralVariables.band;
        String key = KEY_DISCONNECT_COUNT + currentFreq;
        int disconnectCount = prefs.getInt(key, 0);

        if (disconnectCount >= MAX_DISCONNECT_BEFORE_STOP) {
            return true;
        }
        return false;
    }


    /**
     * Increment disconnect counter for current frequency
     */
    public void incrementUsbDisconnectCounter() {
        SharedPreferences prefs = getSharedPreferences(PREFS_USB, Context.MODE_PRIVATE);
        long currentFreq = GeneralVariables.band;
        String key = KEY_DISCONNECT_COUNT + currentFreq;
        int count = prefs.getInt(key, 0) + 1;
        prefs.edit().putInt(key, count).apply();
        Log.d(TAG, "USB disconnect count for freq " + currentFreq + ": " + count);

        if (count >= MAX_DISCONNECT_BEFORE_STOP) {
            Log.w(TAG, "USB disconnect threshold reached for freq " + currentFreq);
        }
    }


    /**
     * Reset disconnect counter for current frequency (call after successful operation)
     */
    public void resetUsbDisconnectCounter() {
        SharedPreferences prefs = getSharedPreferences(PREFS_USB, Context.MODE_PRIVATE);
        long currentFreq = GeneralVariables.band;
        String key = KEY_DISCONNECT_COUNT + currentFreq;
        prefs.edit().remove(key).apply();
        Log.d(TAG, "USB disconnect counter reset for freq " + currentFreq);
    }


    private void animationImage() {

        ObjectAnimator navigationAnimator = ObjectAnimator.ofFloat(binding.navView, "translationY", 200);
        navigationAnimator.setDuration(3000);
        navigationAnimator.setFloatValues(200, 200, 200, 0);


        ObjectAnimator hideLogoAnimator = ObjectAnimator.ofFloat(binding.initDataLayout, "alpha", 1f, 1f, 1f, 0);
        hideLogoAnimator.setDuration(3000);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(navigationAnimator, hideLogoAnimator);
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {

            }

            @Override
            public void onAnimationEnd(Animator animator) {
                binding.initDataLayout.setVisibility(View.GONE);
                binding.utcProgressBar.setVisibility(View.VISIBLE);
                InitFloatView();
            }

            @Override
            public void onAnimationCancel(Animator animator) {

            }

            @Override
            public void onAnimationRepeat(Animator animator) {

            }
        });

        animatorSet.start();
    }


    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            mainViewModel.getUsbDevice();
        } else {
            setIntent(intent);
            doReceiveShareFile(getIntent());
        }

        // [NEW] Обработка флага открытия настроек из FloatView
        if (intent != null && intent.getBooleanExtra("OPEN_CONFIG", false)) {
            // Небольшая задержка, чтобы гарантировать, что активность уже активна
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (navController != null) {
                    navController.navigate(R.id.menu_nav_config);
                }
            }, 100);
        }

        super.onNewIntent(intent);
    }


    @Override
    public void onBackPressed() {
        if (navController.getGraph().getStartDestination() == navController.getCurrentDestination().getId()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.exit_confirmation))
                    .setPositiveButton(getString(R.string.exit), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            // 1. Останавливаем передачу
                            if (mainViewModel.ft8TransmitSignal.isActivated()) {
                                mainViewModel.ft8TransmitSignal.setActivated(false);
                            }

                            // 2. Полная остановка аудио-захвата
                            if (mainViewModel.hamRecorder != null) {
                                mainViewModel.hamRecorder.stopRecord();
                            }

                            // 3. Останавливаем фоновый сервис записи
                            try {
                                com.bg7yoz.ft8cn.service.RecordingForegroundService.stop(MainActivity.this);
                            } catch (Exception e) {
                                // Игнорируем ошибки остановки сервиса
                            }

                            // 4. Сбрасываем UI-флаги
                            mainViewModel.mutableIsRecording.postValue(false);
                            mainViewModel.mutableHamRecordIsRunning.postValue(false);

                            closeThisApp();
                        }
                    }).setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            dialogInterface.dismiss();
                        }
                                    });
            builder.create().show();

        } else {
            navController.navigateUp();
        }
    }

    private void closeThisApp() {
        mainViewModel.ft8TransmitSignal.setActivated(false);
        if (mainViewModel.baseRig != null) {
            if (mainViewModel.baseRig.getConnector() != null) {
                mainViewModel.baseRig.getConnector().disconnect();
            }
        }

        mainViewModel.ft8SignalListener.stopListen();
        mainViewModel = null;
        System.exit(0);
    }


    private void registerBluetoothReceiver() {
        if (mReceive == null) {
            mReceive = new BluetoothStateBroadcastReceive(getApplicationContext(), mainViewModel);
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE);
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_STATE);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothAdapter.EXTRA_CONNECTION_STATE);
        intentFilter.addAction(BluetoothAdapter.EXTRA_STATE);
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_OFF");
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_ON");
        registerReceiver(mReceive, intentFilter);
    }


    private void unregisterBluetoothReceiver() {
        if (mReceive != null) {
            unregisterReceiver(mReceive);
            mReceive = null;
        }
    }

    @Override
    protected void onDestroy() {
        unregisterBluetoothReceiver();
        if (Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue())) {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }

        super.onDestroy();
    }
}