package com.bg7yoz.ft8cn.ui;

/**
 * Settings interface.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.FAQActivity;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.database.RigNameList;
import com.bg7yoz.ft8cn.databinding.FragmentConfigBinding;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.html.LogHttpServer;
import com.bg7yoz.ft8cn.log.ThirdPartyService;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.rigs.InstructionSet;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A simple {@link Fragment} subclass.
 * create an instance of this fragment.
 */
public class ConfigFragment extends Fragment {
    private static final String TAG = "ConfigFragment";
    private static final int DEFAULT_WEB_PORT = 7050;
    private static final int MIN_WEB_PORT = 1024;
    private static final int MAX_WEB_PORT = 65535;

    // Request codes for file operations
    private static final int REQUEST_SAVE_CONF = 1001;
    private static final int REQUEST_LOAD_CONF = 1002;
    private static final int REQUEST_EXPORT_LOGCAT = 1003;

    private MainViewModel mainViewModel;
    private FragmentConfigBinding binding;
    private BandsSpinnerAdapter bandsSpinnerAdapter;
    private BauRateSpinnerAdapter bauRateSpinnerAdapter;
    private SerialDataBitsSpinnerAdapter dataBitsSpinnerAdapter;
    private SerialParityBitsSpinnerAdapter parityBitsSpinnerAdapter;
    private SerialStopBitsSpinnerAdapter stopBitsSpinnerAdapter;
    private RigNameSpinnerAdapter rigNameSpinnerAdapter;
    private LaunchSupervisionSpinnerAdapter launchSupervisionSpinnerAdapter;
    private PttDelaySpinnerAdapter pttDelaySpinnerAdapter;
    private NoReplyLimitSpinnerAdapter noReplyLimitSpinnerAdapter;

    // === Rig connection status fields ===
    private TextView tvRigConnectionStatus;
    private Button btnConnectRig;
    // =================================================

    // [NEW] Database statistics TextViews
    private TextView dbSizeText;
    private TextView qsoCountText;
    private TextView swlCountText;
    // ===================================

    // Flag: use auto mode (0.0 in spinner)
    private boolean isAutoOffsetMode = true;

    public ConfigFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_SAVE_CONF) {
                handleSaveConf(uri);
            } else if (requestCode == REQUEST_LOAD_CONF) {
                handleLoadConf(uri);
            } else if (requestCode == REQUEST_EXPORT_LOGCAT) {
                handleExportLogcat(uri);
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Web port input watcher
    private final TextWatcher onWebPortEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            try {
                int port = Integer.parseInt(editable.toString().trim());
                if (port >= GeneralVariables.MIN_WEB_PORT && port <= GeneralVariables.MAX_WEB_PORT) {
                    binding.inputWebPortEdit.setTextColor(requireContext().getColor(R.color.text_view_color));
                    writeConfig("webPort", String.valueOf(port));
                    GeneralVariables.webPort = port;
                    if (mainViewModel != null) {
                        mainViewModel.restartHttpServer(port);
                    }
                } else {
                    binding.inputWebPortEdit.setTextColor(requireContext().getColor(R.color.text_view_error_color));
                }
            } catch (NumberFormatException e) {
                binding.inputWebPortEdit.setTextColor(requireContext().getColor(R.color.text_view_error_color));
            }
        }
    };

    // HRDLog URL
    private final TextWatcher onHrdlogUrlChanged = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {
            GeneralVariables.hrdlogUrl = s.toString().trim();
            writeConfig("hrdlogUrl", GeneralVariables.hrdlogUrl);
        }
    };

    // HRDLog API Key [SECURE]
    private final TextWatcher onHrdlogApiKeyChanged = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {
            GeneralVariables.hrdlogApiKey = s.toString().trim();
            // [SECURITY] Use secure storage for API key
            writeSensitiveConfig("hrdlogApiKey", GeneralVariables.hrdlogApiKey);
        }
    };

    // HRDLog Username
    private final TextWatcher onHrdlogUsernameChanged = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {
            GeneralVariables.hrdlogUsername = s.toString().trim();
            writeConfig("hrdlogUsername", GeneralVariables.hrdlogUsername);
        }
    };

    // HRDLog Password [SECURE]
    private final TextWatcher onHrdlogPasswordChanged = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {
            GeneralVariables.hrdlogPassword = s.toString();
            // [SECURITY] Use secure storage for password
            writeSensitiveConfig("hrdlogPassword", GeneralVariables.hrdlogPassword);
        }
    };

    // HRDLog Callsign
    private final TextWatcher onHrdlogCallsignChanged = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {
            GeneralVariables.hrdlogCallsign = s.toString().toUpperCase().trim();
            writeConfig("hrdlogCallsign", GeneralVariables.hrdlogCallsign);
        }
    };

    // My grid location
    private final TextWatcher onGridEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            StringBuilder s = new StringBuilder();
            for (int j = 0; j < binding.inputMyGridEdit.getText().length(); j++) {
                if (j < 2) {
                    s.append(Character.toUpperCase(binding.inputMyGridEdit.getText().charAt(j)));
                } else {
                    s.append(Character.toLowerCase(binding.inputMyGridEdit.getText().charAt(j)));
                }
            }
            writeConfig("grid", s.toString());
            GeneralVariables.setMyMaidenheadGrid(s.toString());
        }
    };

    // My callsign
    private final TextWatcher onMyCallEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            writeConfig("callsign", editable.toString().toUpperCase().trim());
            String callsign = editable.toString().toUpperCase().trim();
            if (callsign.length() > 0) {
                Ft8Message.hashList.addHash(FT8Package.getHash22(callsign), callsign);
                Ft8Message.hashList.addHash(FT8Package.getHash12(callsign), callsign);
                Ft8Message.hashList.addHash(FT8Package.getHash10(callsign), callsign);
            }
            GeneralVariables.myCallsign = (editable.toString().toUpperCase().trim());
        }
    };

    // Transmit frequency
    private final TextWatcher onFreqEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            setfreq(editable.toString());
        }
    };

    // Transmit delay time
    private final TextWatcher onTransDelayEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            int transDelay = 1000;
            if (editable.toString().matches("^\\d{1,4}$")) {
                transDelay = Integer.parseInt(editable.toString());
            }
            GeneralVariables.transmitDelay = transDelay;
            mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay);
            writeConfig("transDelay", Integer.toString(transDelay));
        }
    };

    // Cloudlog address
    private final TextWatcher onCloudlogAddressChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.cloudlogServerAddress = editable.toString();
            writeConfig("cloudlogServerAddress", GeneralVariables.getCloudlogServerAddress());
        }
    };

    // Cloudlog APIKEY [SECURE]
    private final TextWatcher onCloudlogApiKeyChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.cloudlogApiKey = editable.toString();
            // [SECURITY] Use secure storage for API key
            writeSensitiveConfig("cloudlogApiKey", GeneralVariables.getCloudlogServerApiKey());
        }
    };

    // Cloudlog Station ID
    private final TextWatcher onCloudlogStationIDChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.cloudlogStationID = editable.toString();
            writeConfig("cloudlogStationID", GeneralVariables.getCloudlogStationID());
        }
    };

    // QRZ API key [SECURE]
    private final TextWatcher onQrzApiKeyChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.qrzApiKey = editable.toString();
            // [SECURITY] Use secure storage for API key
            writeSensitiveConfig("qrzApiKey", GeneralVariables.getQrzApiKey());
        }
    };

    // Excluded callsign prefixes
    private final TextWatcher onExcludedCallsigns = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.addExcludedCallsigns(editable.toString());
            writeConfig("excludedCallsigns", GeneralVariables.getExcludeCallsigns());
        }
    };

    // Modifier
    private final TextWatcher onModifierEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            if (editable.toString().toUpperCase().trim().matches("[0-9]{3}|[A-Z]{1,4}")
                    || editable.toString().trim().length() == 0) {
                binding.modifierEdit.setTextColor(requireContext().getColor(R.color.text_view_color));
                GeneralVariables.toModifier = editable.toString().toUpperCase().trim();
                writeConfig("toModifier", GeneralVariables.toModifier);
            } else {
                binding.modifierEdit.setTextColor(requireContext().getColor(R.color.text_view_error_color));
            }
        }
    };

    // CI-V address
    private final TextWatcher onCIVAddressEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}
        @Override
        public void afterTextChanged(Editable editable) {
            if (editable.toString().length() < 2) {
                return;
            }
            String s = "0x" + editable.toString();
            if (s.matches("\\b0[xX][0-9a-fA-F]+\\b")) {
                String temp = editable.toString().substring(0, 2).toUpperCase();
                writeConfig("civ", temp);
                GeneralVariables.civAddress = Integer.parseInt(temp, 16);
                mainViewModel.setCivAddress();
            }
        }
    };

    @SuppressLint("DefaultLocale")
    private void setfreq(String sFreq) {
        float freq;
        try {
            freq = Float.parseFloat(sFreq);
            if (freq < 100) {
                freq = 100;
            }
            if (freq > 2900) {
                freq = 2900;
            }
        } catch (Exception e) {
            freq = 1000;
        }
        writeConfig("freq", String.format("%.0f", freq));
        GeneralVariables.setBaseFrequency(freq);
    }

    /**
     * [NEW] Write sensitive config value using secure storage (if available).
     * Fallback to plain config table for legacy devices or errors.
     * @param key Config key name
     * @param value Plain text value
     */
    private void writeSensitiveConfig(String key, String value) {
        if (mainViewModel != null && mainViewModel.databaseOpr != null) {
            mainViewModel.databaseOpr.saveSensitiveConfig(key, value);
        } else {
            // Fallback: write to plain config table
            writeConfig(key, value);
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentConfigBinding.inflate(inflater, container, false);

        // === Initialize rig connection status fields ===
        tvRigConnectionStatus = binding.getRoot().findViewById(R.id.tvRigConnectionStatus);
        btnConnectRig = binding.getRoot().findViewById(R.id.btnConnectRig);

        // [NEW] Initialize database statistics TextViews
        // [NEW] Initialize database statistics TextViews (direct access)
// [NEW] Initialize database statistics TextViews (direct access)
        View root = binding.getRoot();
        dbSizeText = root.findViewById(com.bg7yoz.ft8cn.R.id.dbSizeText);
        qsoCountText = root.findViewById(com.bg7yoz.ft8cn.R.id.qsoCountText);
        swlCountText = root.findViewById(com.bg7yoz.ft8cn.R.id.swlCountText);
        // ==============================================

        // Observer for connection status
        if (mainViewModel.rigStatusText != null) {
            mainViewModel.rigStatusText.observe(getViewLifecycleOwner(), new Observer<String>() {
                @Override
                public void onChanged(String status) {
                    if (tvRigConnectionStatus != null) {
                        tvRigConnectionStatus.setText(status);
                        int color = status.startsWith("Connected") || status.startsWith("VOX")
                                ? requireContext().getColor(R.color.text_view_color)
                                : requireContext().getColor(android.R.color.darker_gray);
                        tvRigConnectionStatus.setTextColor(color);
                    }
                    if (btnConnectRig != null) {
                        btnConnectRig.setText(mainViewModel.isRigConnected() ? "Disconnect" : "Connect");
                        btnConnectRig.setEnabled(GeneralVariables.controlMode != ControlMode.VOX);
                    }
                }
            });
        }
        // =================================================

        // Connect/Disconnect button handler
        if (btnConnectRig != null) {
            btnConnectRig.setOnClickListener(v -> {
                if (mainViewModel != null) {
                    boolean isConnected = mainViewModel.isRigConnected();
                    Log.d("RigConnectBtn", "Clicked. State: " + (isConnected ? "CONNECTED" : "DISCONNECTED"));

                    // Check if network mode has valid settings
                    if (!isConnected && GeneralVariables.connectMode == ConnectMode.NETWORK) {
                        String savedIp = GeneralVariables.getNetworkRigIp();
                        int savedPort = GeneralVariables.getNetworkRigPort();
                        if (savedIp == null || savedIp.isEmpty() || savedPort <= 0) {
                            ToastMessage.show("Enter IP/Port in Network settings first");
                            new LoginIcomRadioDialog(requireContext(), mainViewModel).show();
                            return;
                        }
                    }

                    ToastMessage.show(isConnected ? "Disconnect rig..." : "Connect to rig...");
                    mainViewModel.toggleRigConnection(requireContext());
                }
            });
        }
        // =================================================

        // [NEW] Update database statistics on load
        updateDatabaseStatistics();
        // ==============================================

        // Initialize: determine mode (auto or manual)
        isAutoOffsetMode = (UtcTimer.delay == 0);
        updateOffsetDisplay();

        // Set UTC time offset spinner
        setUtcTimeOffsetSpinner();
        // Set PTT delay spinner
        setPttDelaySpinner();
        // Set operation band spinner
        setBandsSpinner();
        // Set baud rate list
        setBauRateSpinner();
        // Set data bits list
        setDataBitsSpinner();
        // Set parity bits
        setParityBitsSpinner();
        // Set stop bits
        setStopBitsSpinner();
        // Set rig name list
        setRigNameSpinner();
        // Set decode mode
        setDecodeMode();
        // Set audio output bits mode
        setAudioOutputBitsMode();
        // Set audio output sample rate
        setAudioOutputRateMode();
        // Set message display mode
        setMessageMode();
        // Set control mode VOX CAT
        setControlMode();
        // Set connection mode
        setConnectMode();
        // Set transmit supervision list
        setLaunchSupervision();
        // Set help dialogs
        setHelpDialog();
        // Set no reply limit spinner
        setNoReplyLimitSpinner();
        // Set spinner OnItemSelected events
        setSpinnerOnItemSelected();

        // === Initial rig status update ===
        if (mainViewModel != null) {
            mainViewModel.updateRigStatus();
        }
        // =================================================

        // Show scroll arrows after delay
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                setScrollImageVisible();
            }
        }, 1000);
        binding.scrollView3.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View view, int i, int i1, int i2, int i3) {
                setScrollImageVisible();
            }
        });

        // FAQ button onClick
        binding.faqButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(requireContext(), FAQActivity.class);
                startActivity(intent);
            }
        });

        // === Bottom buttons: Save Conf, Load Conf, LogCat, About ===
        if (binding.btnSaveConf != null) {
            binding.btnSaveConf.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "FT8CN_Settings.json");
                startActivityForResult(intent, REQUEST_SAVE_CONF);
            });
        }

        if (binding.btnLoadConf != null) {
            binding.btnLoadConf.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, REQUEST_LOAD_CONF);
            });
        }

        if (binding.btnLogcat != null) {
            binding.btnLogcat.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "ft8cn_logcat.txt");
                startActivityForResult(intent, REQUEST_EXPORT_LOGCAT);
            });
        }

        if (binding.aboutButton != null) {
            binding.aboutButton.setText("About");
        }
        // ===================================================

        // === Web Port Configuration ===
        int savedWebPort = GeneralVariables.getWebPort();
        if (savedWebPort <= 0) savedWebPort = DEFAULT_WEB_PORT;
        binding.inputWebPortEdit.removeTextChangedListener(onWebPortEditorChanged);
        binding.inputWebPortEdit.setText(String.valueOf(savedWebPort));
        binding.inputWebPortEdit.addTextChangedListener(onWebPortEditorChanged);
        if (savedWebPort >= MIN_WEB_PORT && savedWebPort <= MAX_WEB_PORT) {
            binding.inputWebPortEdit.setTextColor(requireContext().getColor(R.color.text_view_color));
        } else {
            binding.inputWebPortEdit.setTextColor(requireContext().getColor(R.color.text_view_error_color));
        }
        // === End Web Port Configuration ===

        // Maidenhead grid
        binding.inputMyGridEdit.removeTextChangedListener(onGridEditorChanged);
        binding.inputMyGridEdit.setText(GeneralVariables.getMyMaidenheadGrid());
        binding.inputMyGridEdit.addTextChangedListener(onGridEditorChanged);

        // My callsign
        binding.inputMycallEdit.removeTextChangedListener(onMyCallEditorChanged);
        binding.inputMycallEdit.setText(GeneralVariables.myCallsign);
        binding.inputMycallEdit.addTextChangedListener(onMyCallEditorChanged);

        // Modifier
        binding.modifierEdit.removeTextChangedListener(onModifierEditorChanged);
        binding.modifierEdit.setText(GeneralVariables.toModifier);
        binding.modifierEdit.addTextChangedListener(onModifierEditorChanged);

        // Transmit frequency
        binding.inputFreqEditor.removeTextChangedListener(onFreqEditorChanged);
        binding.inputFreqEditor.setText(GeneralVariables.getBaseFrequencyStr());
        binding.inputFreqEditor.addTextChangedListener(onFreqEditorChanged);

        // CIV address
        binding.civAddressEdit.removeTextChangedListener(onCIVAddressEditorChanged);
        binding.civAddressEdit.setText(GeneralVariables.getCivAddressStr());
        binding.civAddressEdit.addTextChangedListener(onCIVAddressEditorChanged);

        // === TUNE Command Configuration ===
        binding.sendTuneOnFreqChangeSwitch.setOnCheckedChangeListener(null);
        binding.sendTuneOnFreqChangeSwitch.setChecked(GeneralVariables.sendTuneOnFreqChange);
        binding.sendTuneOnFreqChangeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                GeneralVariables.sendTuneOnFreqChange = isChecked;
                buttonView.setText(isChecked ?
                        GeneralVariables.getStringFromResource(R.string.switch_on) :
                        GeneralVariables.getStringFromResource(R.string.switch_off));
                writeConfig("sendTuneOnFreqChange", isChecked ? "1" : "0");
            }
        });
        binding.sendTuneOnFreqChangeSwitch.setText(GeneralVariables.sendTuneOnFreqChange ?
                GeneralVariables.getStringFromResource(R.string.switch_on) :
                GeneralVariables.getStringFromResource(R.string.switch_off));

        // === Clear Call Hist on Freq Change Configuration ===
        binding.clearCallHistOnFreqChangeSwitch.setOnCheckedChangeListener(null);
        binding.clearCallHistOnFreqChangeSwitch.setChecked(GeneralVariables.clearCallHistOnFreqChange);
        binding.clearCallHistOnFreqChangeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                GeneralVariables.clearCallHistOnFreqChange = isChecked;
                buttonView.setText(isChecked ?
                        GeneralVariables.getStringFromResource(R.string.switch_on) :
                        GeneralVariables.getStringFromResource(R.string.switch_off));
                writeConfig("clearCallHistOnFreqChange", isChecked ? "1" : "0");
                Log.d(TAG, "ClearCallHistOnFreqChange set to: " + isChecked);
            }
        });
        binding.clearCallHistOnFreqChangeSwitch.setText(GeneralVariables.clearCallHistOnFreqChange ?
                GeneralVariables.getStringFromResource(R.string.switch_on) :
                GeneralVariables.getStringFromResource(R.string.switch_off));
        // === End Clear Call Hist Configuration ===

        // Transmit delay
        binding.inputTransDelayEdit.removeTextChangedListener(onTransDelayEditorChanged);
        binding.inputTransDelayEdit.setText(GeneralVariables.getTransmitDelayStr());
        binding.inputTransDelayEdit.addTextChangedListener(onTransDelayEditorChanged);

        binding.excludedCallsignEdit.removeTextChangedListener(onExcludedCallsigns);
        binding.excludedCallsignEdit.setText(GeneralVariables.getExcludeCallsigns());
        binding.excludedCallsignEdit.addTextChangedListener(onExcludedCallsigns);

        // Cloudlog configuration
        binding.cloudlogServerAddressEdit.removeTextChangedListener(onCloudlogAddressChanged);
        binding.cloudlogServerAddressEdit.setText(GeneralVariables.getCloudlogServerAddress());
        binding.cloudlogServerAddressEdit.addTextChangedListener(onCloudlogAddressChanged);

        binding.cloudlogServerApiKeyEdit.removeTextChangedListener(onCloudlogApiKeyChanged);
        binding.cloudlogServerApiKeyEdit.setText(GeneralVariables.getCloudlogServerApiKey());
        binding.cloudlogServerApiKeyEdit.addTextChangedListener(onCloudlogApiKeyChanged);

        binding.cloudlogStationIdEdit.removeTextChangedListener(onCloudlogStationIDChanged);
        binding.cloudlogStationIdEdit.setText(GeneralVariables.getCloudlogStationID());
        binding.cloudlogStationIdEdit.addTextChangedListener(onCloudlogStationIDChanged);

        // QRZ configuration
        binding.qrzApiKeyTextEdit.removeTextChangedListener(onQrzApiKeyChanged);
        binding.qrzApiKeyTextEdit.setText(GeneralVariables.getQrzApiKey());
        binding.qrzApiKeyTextEdit.addTextChangedListener(onQrzApiKeyChanged);

        // Set sync frequency switch
        binding.synFrequencySwitch.setOnCheckedChangeListener(null);
        binding.synFrequencySwitch.setChecked(GeneralVariables.synFrequency);
        setSyncFreqText();
        binding.synFrequencySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (binding.synFrequencySwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("synFreq", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("synFreq", "0", null);
                    setfreq(binding.inputFreqEditor.getText().toString());
                }
                GeneralVariables.synFrequency = binding.synFrequencySwitch.isChecked();
                setSyncFreqText();
                binding.inputFreqEditor.setEnabled(!binding.synFrequencySwitch.isChecked());
            }
        });

        // Set PTT delay spinner selection
        binding.pttDelayOffsetSpinner.setSelection(GeneralVariables.pttDelay / 10);
        // Get operation band
        binding.operationBandSpinner.setSelection(GeneralVariables.bandListIndex);
        // Get rig model
        binding.rigNameSpinner.setSelection(GeneralVariables.modelNo);
        // Serial data bits
        binding.dataBitsSpinner.setSelection(dataBitsSpinnerAdapter.getPosition(GeneralVariables.serialDataBits));
        // Serial stop bits
        binding.stopBitsSpinner.setSelection(stopBitsSpinnerAdapter.getPosition(GeneralVariables.serialStopBits));
        binding.parityBitsSpinner.setSelection(parityBitsSpinnerAdapter.getPosition(GeneralVariables.serialParity));
        // Get baud rate
        binding.baudRateSpinner.setSelection(bauRateSpinnerAdapter.getPosition(GeneralVariables.baudRate));
        // Set transmit supervision
        binding.launchSupervisionSpinner.setSelection(launchSupervisionSpinnerAdapter.getPosition(GeneralVariables.launchSupervision));
        // Set no reply limit
        binding.noResponseCountSpinner.setSelection(GeneralVariables.noReplyLimit);

        // Set auto follow CQ switch
        binding.followCQSwitch.setOnCheckedChangeListener(null);
        binding.followCQSwitch.setChecked(GeneralVariables.autoFollowCQ);
        setAutoFollowCQText();
        binding.followCQSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.autoFollowCQ = binding.followCQSwitch.isChecked();
                if (binding.followCQSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("autoFollowCQ", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("autoFollowCQ", "0", null);
                }
                setAutoFollowCQText();
            }
        });

        // Set SWR alarm switch
        binding.swrAlarmSwitch.setOnCheckedChangeListener(null);
        binding.swrAlarmSwitch.setChecked(GeneralVariables.swr_switch_on);
        setSwrAlarmSwitchText();
        binding.swrAlarmSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.swr_switch_on = binding.swrAlarmSwitch.isChecked();
                if (binding.swrAlarmSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("swrSwitch", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("swrSwitch", "0", null);
                }
                setSwrAlarmSwitchText();
            }
        });

        // Set ALC alarm switch
        binding.alcAlarmSwitch.setOnCheckedChangeListener(null);
        binding.alcAlarmSwitch.setChecked(GeneralVariables.alc_switch_on);
        setAlcAlarmSwitchText();
        binding.alcAlarmSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.alc_switch_on = binding.alcAlarmSwitch.isChecked();
                if (binding.alcAlarmSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("alcSwitch", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("alcSwitch", "0", null);
                }
                setAlcAlarmSwitchText();
            }
        });

        // Set auto call follow switch
        binding.autoCallfollowSwitch.setOnCheckedChangeListener(null);
        binding.autoCallfollowSwitch.setChecked(GeneralVariables.autoCallFollow);
        setAutoCallFollow();
        binding.autoCallfollowSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.autoCallFollow = binding.autoCallfollowSwitch.isChecked();
                if (binding.autoCallfollowSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("autoCallFollow", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("autoCallFollow", "0", null);
                }
                setAutoCallFollow();
            }
        });

        // Set save SWL option
        binding.saveSWLSwitch.setOnCheckedChangeListener(null);
        binding.saveSWLSwitch.setChecked(GeneralVariables.saveSWLMessage);
        setSaveSwl();
        binding.saveSWLSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.saveSWLMessage = binding.saveSWLSwitch.isChecked();
                if (binding.saveSWLSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("saveSWL", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("saveSWL", "0", null);
                }
                setSaveSwl();
            }
        });

        // Set save SWL QSO option
        binding.saveSWLQSOSwitch.setOnCheckedChangeListener(null);
        binding.saveSWLQSOSwitch.setChecked(GeneralVariables.saveSWLMessage);
        setSaveSwlQSO();
        binding.saveSWLQSOSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.saveSWL_QSO = binding.saveSWLQSOSwitch.isChecked();
                if (binding.saveSWLQSOSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("saveSWLQSO", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("saveSWLQSO", "0", null);
                }
                setSaveSwlQSO();
            }
        });

        // Set save Cloudlog option
        binding.enableCloudlogSwitch.setOnCheckedChangeListener(null);
        binding.enableCloudlogSwitch.setChecked(GeneralVariables.enableCloudlog);
        binding.enableCloudlogSwitch.setText(GeneralVariables.getStringFromResource(R.string.config_enable_cloudlog)
                + (GeneralVariables.enableCloudlog ? "(On)" : "(Off)"));
        binding.enableCloudlogSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.enableCloudlog = binding.enableCloudlogSwitch.isChecked();
                if (binding.enableCloudlogSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("enableCloudlog", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("enableCloudlog", "0", null);
                }
                binding.enableCloudlogSwitch.setText(GeneralVariables.getStringFromResource(R.string.config_enable_cloudlog)
                        + (GeneralVariables.enableCloudlog ? "(On)" : "(Off)"));
            }
        });

        // Set save QRZ option
        binding.enableQrzSwitch.setOnCheckedChangeListener(null);
        binding.enableQrzSwitch.setChecked(GeneralVariables.enableQRZ);
        binding.enableQrzSwitch.setText(GeneralVariables.getStringFromResource(R.string.config_enable_qrz)
                + (GeneralVariables.enableQRZ ? "(On)" : "(Off)"));
        binding.enableQrzSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.enableQRZ = binding.enableQrzSwitch.isChecked();
                if (binding.enableQrzSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("enableQRZ", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("enableQRZ", "0", null);
                }
                binding.enableQrzSwitch.setText(GeneralVariables.getStringFromResource(R.string.config_enable_qrz)
                        + (GeneralVariables.enableQRZ ? "(On)" : "(Off)"));
            }
        });

        // Get Maidenhead grid
        binding.configGetGridImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String grid = MaidenheadGrid.getMyMaidenheadGrid(getContext());
                if (!grid.equals("")) {
                    binding.inputMyGridEdit.setText(grid);
                }
            }
        });

        // === HRDLog.net Configuration ===
        binding.enableHrdlogSwitch.setOnCheckedChangeListener(null);
        binding.enableHrdlogSwitch.setChecked(GeneralVariables.enableHrdlog);
        binding.enableHrdlogSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                GeneralVariables.enableHrdlog = isChecked;
                writeConfig("enableHrdlog", isChecked ? "1" : "0");
            }
        });

        binding.hrdlogUrlEdit.removeTextChangedListener(onHrdlogUrlChanged);
        binding.hrdlogUrlEdit.setText(GeneralVariables.hrdlogUrl);
        binding.hrdlogUrlEdit.addTextChangedListener(onHrdlogUrlChanged);

        binding.hrdlogApiKeyEdit.removeTextChangedListener(onHrdlogApiKeyChanged);
        binding.hrdlogApiKeyEdit.setText(GeneralVariables.hrdlogApiKey);
        binding.hrdlogApiKeyEdit.addTextChangedListener(onHrdlogApiKeyChanged);

        binding.hrdlogUsernameEdit.removeTextChangedListener(onHrdlogUsernameChanged);
        binding.hrdlogUsernameEdit.setText(GeneralVariables.hrdlogUsername);
        binding.hrdlogUsernameEdit.addTextChangedListener(onHrdlogUsernameChanged);

        binding.hrdlogPasswordEdit.removeTextChangedListener(onHrdlogPasswordChanged);
        binding.hrdlogPasswordEdit.setText(GeneralVariables.hrdlogPassword);
        binding.hrdlogPasswordEdit.addTextChangedListener(onHrdlogPasswordChanged);

        binding.hrdlogCallsignEdit.removeTextChangedListener(onHrdlogCallsignChanged);
        binding.hrdlogCallsignEdit.setText(GeneralVariables.hrdlogCallsign);
        binding.hrdlogCallsignEdit.addTextChangedListener(onHrdlogCallsignChanged);
        // === End HRDLog.net Configuration ===

        // Serial default values reset button
        binding.serialDefaultButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GeneralVariables.serialParity = 0;
                GeneralVariables.serialDataBits = 8;
                GeneralVariables.serialStopBits = 1;
                requireActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        binding.parityBitsSpinner.setSelection(parityBitsSpinnerAdapter.getPosition(GeneralVariables.serialParity));
                        binding.dataBitsSpinner.setSelection(dataBitsSpinnerAdapter.getPosition(GeneralVariables.serialDataBits));
                        binding.stopBitsSpinner.setSelection(stopBitsSpinnerAdapter.getPosition(GeneralVariables.serialStopBits));
                    }
                });
            }
        });

        // === BACK PRESS HANDLER: Save connectMode before exit ===
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        // Save current connect mode selection
                        int buttonId = binding.connectModeRadioGroup.getCheckedRadioButtonId();
                        if (buttonId == binding.cableConnectRadioButton.getId()) {
                            GeneralVariables.connectMode = ConnectMode.USB_CABLE;
                        } else if (buttonId == binding.bluetoothConnectRadioButton.getId()) {
                            GeneralVariables.connectMode = ConnectMode.BLUE_TOOTH;
                        } else if (buttonId == binding.networkConnectRadioButton.getId()) {
                            GeneralVariables.connectMode = ConnectMode.NETWORK;
                        }
                        writeConfig("connectMode", String.valueOf(GeneralVariables.connectMode));

                        // Allow default back behavior
                        setEnabled(false);
                        requireActivity().onBackPressed();
                    }
                });
        // ==================================================

        // [NEW] Accept DX Calls: восстановление значения при загрузке
        binding.acceptDxCallsSwitch.setChecked(GeneralVariables.acceptDxCalls);

        // [NEW] Accept DX Calls: обработчик изменения
        binding.acceptDxCallsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GeneralVariables.acceptDxCalls = isChecked;
            // Сохраняем в базу
            GeneralVariables.saveAcceptDxCallsToDatabase(mainViewModel.databaseOpr);

            // Показываем уведомление о необходимости перезапуска
            ToastMessage.show(isChecked ?
                    "DX Multistream: ON (restart recommended)" :
                    "DX Multistream: OFF");
        });
        // [END NEW]

        return binding.getRoot();
    }

    /**
     * [NEW] Update database statistics display
     */
    /**
     * [NEW] Update database statistics display
     */
    private void updateDatabaseStatistics() {
        if (dbSizeText == null || qsoCountText == null || swlCountText == null) return;

        new Thread(() -> {
            try {
                // 1. Database file size
                File dbFile = requireContext().getDatabasePath("data.db");
                final String sizeText;  // [FIX] Declare as final for lambda
                if (dbFile.exists()) {
                    long sizeKB = dbFile.length() / 1024;
                    sizeText = sizeKB >= 1024
                            ? String.format(Locale.US, "Size: %.1f MB", sizeKB / 1024.0)
                            : "Size: " + sizeKB + " KB";
                } else {
                    sizeText = "Size: --";
                }

                // 2. QSO count
                Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery("SELECT COUNT(*) FROM QSLTable", null);
                final int qsoCount;  // [FIX] Declare as final for lambda
                if (cursor != null && cursor.moveToFirst()) {
                    qsoCount = cursor.getInt(0);
                    cursor.close();
                } else {
                    qsoCount = 0;
                }

                // 3. SWL messages count
                cursor = mainViewModel.databaseOpr.getDb().rawQuery("SELECT COUNT(*) FROM SWLMessages", null);
                final int swlCount;  // [FIX] Declare as final for lambda
                if (cursor != null && cursor.moveToFirst()) {
                    swlCount = cursor.getInt(0);
                    cursor.close();
                } else {
                    swlCount = 0;
                }

                // Update UI on main thread
                requireActivity().runOnUiThread(() -> {
                    dbSizeText.setText(sizeText);
                    qsoCountText.setText("QSO Log: " + qsoCount);
                    swlCountText.setText("SWL Messages: " + swlCount);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error getting database statistics: " + e.getMessage());
            }
        }).start();
    }
    // ===================================

    /**
     * Handle save settings to JSON file
     */
    private void handleSaveConf(Uri uri) throws Exception {
        OutputStream os = requireContext().getContentResolver().openOutputStream(uri);
        if (os == null) throw new IOException("Cannot open output stream");

        Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery("SELECT KeyName, Value FROM config", null);
        JSONObject json = new JSONObject();
        while (cursor.moveToNext()) {
            json.put(cursor.getString(0), cursor.getString(1));
        }
        cursor.close();

        os.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        os.close();
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show();
    }

    /**
     * Handle load settings from JSON file
     */
    private void handleLoadConf(Uri uri) throws Exception {
        InputStream is = requireContext().getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open input stream");

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) content.append(line);
        reader.close();
        is.close();

        JSONObject json = new JSONObject(content.toString());
        int count = 0;
        org.json.JSONArray names = json.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                mainViewModel.databaseOpr.writeConfig(key, json.getString(key), null);
                count++;
            }
        }

        Toast.makeText(requireContext(), "Loaded " + count + " settings", Toast.LENGTH_SHORT).show();

        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Restart Required")
                .setMessage("Restart application to apply settings?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    Intent intent = requireActivity().getPackageManager()
                            .getLaunchIntentForPackage(requireContext().getPackageName());
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        requireActivity().startActivity(intent);
                        requireActivity().finish();
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    /**
     * Handle export logcat to text file
     */
    private void handleExportLogcat(Uri uri) throws Exception {
        OutputStream os = requireContext().getContentResolver().openOutputStream(uri);
        if (os == null) throw new IOException("Cannot open output stream");

        Process process = Runtime.getRuntime().exec(
                new String[]{"logcat", "-d", "-v", "threadtime", "com.bg7yoz.ft8cn"});
        InputStream is = process.getInputStream();

        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
        }
        is.close();
        os.close();
        Toast.makeText(requireContext(), "Logcat exported", Toast.LENGTH_SHORT).show();
    }

    /**
     * Set spinner OnItemSelected events to prevent duplicate writes to database on startup
     */
    private void setSpinnerOnItemSelected() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                binding.pttDelayOffsetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.pttDelay = i * 10;
                        writeConfig("pttDelay", String.valueOf(GeneralVariables.pttDelay));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                binding.operationBandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.bandListIndex = i;
                        GeneralVariables.band = OperationBand.getBandFreq(i);

                        // [NEW] Notify observers about band change
                        GeneralVariables.mutableBandChange.postValue(i);

                        mainViewModel.databaseOpr.getAllQSLCallsigns();
                        writeConfig("bandFreq", String.valueOf(GeneralVariables.band));
                        if (GeneralVariables.controlMode == ControlMode.CAT
                                || GeneralVariables.controlMode == ControlMode.RTS
                                || GeneralVariables.controlMode == ControlMode.DTR) {
                            mainViewModel.setOperationBand();
                        }
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                binding.rigNameSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.modelNo = i;
                        writeConfig("model", String.valueOf(i));
                        setAddrAndBauRate(rigNameSpinnerAdapter.getRigName(i));
                        GeneralVariables.instructionSet = rigNameSpinnerAdapter.getRigName(i).instructionSet;
                        writeConfig("instruction", String.valueOf(GeneralVariables.instructionSet));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                binding.baudRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.baudRate = bauRateSpinnerAdapter.getValue(i);
                        writeConfig("baudRate", String.valueOf(GeneralVariables.baudRate));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                binding.launchSupervisionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.launchSupervision = LaunchSupervisionSpinnerAdapter.getTimeOut(i);
                        writeConfig("launchSupervision", String.valueOf(GeneralVariables.launchSupervision));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                binding.noResponseCountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.noReplyLimit = i;
                        writeConfig("noReplyLimit", String.valueOf(GeneralVariables.noReplyLimit));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                // Serial data bits
                binding.dataBitsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.serialDataBits = dataBitsSpinnerAdapter.getValue(i);
                        writeConfig("dataBits", String.valueOf(GeneralVariables.serialDataBits));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                // Serial stop bits
                binding.stopBitsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.serialStopBits = stopBitsSpinnerAdapter.getValue(i);
                        writeConfig("stopBits", String.valueOf(GeneralVariables.serialStopBits));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });

                // Parity bits
                binding.parityBitsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.serialParity = parityBitsSpinnerAdapter.getValue(i);
                        writeConfig("parityBits", String.valueOf(GeneralVariables.serialParity));
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {}
                });
            }
        }, 1000);
    }

    /**
     * Set address and baud rate, instruction set
     * @param rigName rig model
     */
    private void setAddrAndBauRate(RigNameList.RigName rigName) {
        GeneralVariables.civAddress = rigName.address;
        mainViewModel.setCivAddress();
        GeneralVariables.baudRate = rigName.bauRate;
        binding.civAddressEdit.setText(String.format("%X", rigName.address));
        binding.baudRateSpinner.setSelection(bauRateSpinnerAdapter.getPosition(rigName.bauRate));
    }

    /**
     * Set sync frequency switch display text
     */
    private void setSyncFreqText() {
        if (binding.synFrequencySwitch.isChecked()) {
            binding.synFrequencySwitch.setText(getString(R.string.freq_syn));
        } else {
            binding.synFrequencySwitch.setText(getString(R.string.freq_asyn));
        }
    }

    /**
     * Set auto follow CQ switch text
     */
    private void setAutoFollowCQText() {
        if (binding.followCQSwitch.isChecked()) {
            binding.followCQSwitch.setText(getString(R.string.auto_follow_cq));
        } else {
            binding.followCQSwitch.setText(getString(R.string.not_concerned_about_CQ));
        }
    }

    /**
     * Set SWR alarm switch text
     */
    private void setSwrAlarmSwitchText() {
        if (binding.swrAlarmSwitch.isChecked()) {
            binding.swrAlarmSwitch.setText(R.string.swr_switch_on);
        } else {
            binding.swrAlarmSwitch.setText(R.string.swr_switch_off);
        }
    }

    /**
     * Set ALC alarm switch text
     */
    private void setAlcAlarmSwitchText() {
        if (binding.alcAlarmSwitch.isChecked()) {
            binding.alcAlarmSwitch.setText(R.string.alc_switch_on);
        } else {
            binding.alcAlarmSwitch.setText(R.string.alc_switch_off);
        }
    }

    // Set auto call follow text
    private void setAutoCallFollow() {
        if (binding.autoCallfollowSwitch.isChecked()) {
            binding.autoCallfollowSwitch.setText(getString(R.string.automatic_call_following));
        } else {
            binding.autoCallfollowSwitch.setText(getString(R.string.do_not_call_the_following_callsign));
        }
    }

    private void setSaveSwl() {
        if (binding.saveSWLSwitch.isChecked()) {
            binding.saveSWLSwitch.setText(getString(R.string.config_save_swl));
        } else {
            binding.saveSWLSwitch.setText(getString(R.string.config_donot_save_swl));
        }
    }

    private void setSaveSwlQSO() {
        if (binding.saveSWLQSOSwitch.isChecked()) {
            binding.saveSWLQSOSwitch.setText(getString(R.string.config_save_swl_qso));
        } else {
            binding.saveSWLQSOSwitch.setText(getString(R.string.config_donot_save_swl_qso));
        }
    }

    private void setUtcTimeOffsetSpinner() {
        UtcOffsetSpinnerAdapter adapter = new UtcOffsetSpinnerAdapter(requireContext());

        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                binding.utcTimeOffsetSpinner.setOnItemSelectedListener(null);
                binding.utcTimeOffsetSpinner.setAdapter(adapter);
                adapter.notifyDataSetChanged();

                int initialIndex = (UtcTimer.delay / 100 + 75) / 5;
                initialIndex = Math.max(0, Math.min(30, initialIndex));
                binding.utcTimeOffsetSpinner.setSelection(initialIndex);

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        binding.utcTimeOffsetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                            @Override
                            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                                UtcTimer.delay = i * 500 - 7500;
                                writeConfig("timeOffsetSec", String.valueOf(UtcTimer.delay));
                            }
                            @Override
                            public void onNothingSelected(AdapterView<?> adapterView) {}
                        });
                    }
                }, 200);
            }
        });
    }

    /**
     * Set operation band spinner
     */
    private void setBandsSpinner() {
        GeneralVariables.mutableBandChange.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                binding.operationBandSpinner.setSelection(integer);
            }
        });
        bandsSpinnerAdapter = new BandsSpinnerAdapter(requireContext());
        binding.operationBandSpinner.setAdapter(bandsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bandsSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set baud rate list
     */
    private void setBauRateSpinner() {
        bauRateSpinnerAdapter = new BauRateSpinnerAdapter(requireContext());
        binding.baudRateSpinner.setAdapter(bauRateSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bauRateSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set data bits list
     */
    private void setDataBitsSpinner() {
        dataBitsSpinnerAdapter = new SerialDataBitsSpinnerAdapter(requireContext());
        binding.dataBitsSpinner.setAdapter(dataBitsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                dataBitsSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set parity bits list
     */
    private void setParityBitsSpinner() {
        parityBitsSpinnerAdapter = new SerialParityBitsSpinnerAdapter(requireContext());
        binding.parityBitsSpinner.setAdapter(parityBitsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                parityBitsSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set stop bits list
     */
    private void setStopBitsSpinner() {
        stopBitsSpinnerAdapter = new SerialStopBitsSpinnerAdapter(requireContext());
        binding.stopBitsSpinner.setAdapter(stopBitsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stopBitsSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set no reply limit spinner
     */
    private void setNoReplyLimitSpinner() {
        noReplyLimitSpinnerAdapter = new NoReplyLimitSpinnerAdapter(requireContext());
        binding.noResponseCountSpinner.setAdapter(noReplyLimitSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                noReplyLimitSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set transmit supervision list
     */
    private void setLaunchSupervision() {
        launchSupervisionSpinnerAdapter = new LaunchSupervisionSpinnerAdapter(requireContext());
        binding.launchSupervisionSpinner.setAdapter(launchSupervisionSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                launchSupervisionSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set rig name list
     */
    private void setRigNameSpinner() {
        rigNameSpinnerAdapter = new RigNameSpinnerAdapter(requireContext());
        binding.rigNameSpinner.setAdapter(rigNameSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rigNameSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set PTT delay spinner
     */
    private void setPttDelaySpinner() {
        pttDelaySpinnerAdapter = new PttDelaySpinnerAdapter(requireContext());
        binding.pttDelayOffsetSpinner.setAdapter(pttDelaySpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                pttDelaySpinnerAdapter.notifyDataSetChanged();
                binding.pttDelayOffsetSpinner.setSelection(GeneralVariables.pttDelay / 10);
            }
        });
    }

    private void setDecodeMode() {
        binding.decodeModeRadioGroup.clearCheck();
        binding.fastDecodeRadioButton.setChecked(!GeneralVariables.deepDecodeMode);
        binding.deepDecodeRadioButton.setChecked(GeneralVariables.deepDecodeMode);
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.decodeModeRadioGroup.getCheckedRadioButtonId();
                GeneralVariables.deepDecodeMode = buttonId == binding.deepDecodeRadioButton.getId();
                writeConfig("deepMode", GeneralVariables.deepDecodeMode ? "1" : "0");
            }
        };
        binding.fastDecodeRadioButton.setOnClickListener(listener);
        binding.deepDecodeRadioButton.setOnClickListener(listener);
    }

    /**
     * Set audio output bits mode
     */
    private void setAudioOutputBitsMode() {
        binding.audioBitsRadioGroup.clearCheck();
        binding.audio32BitsRadioButton.setChecked(GeneralVariables.audioOutput32Bit);
        binding.audio16BitsRadioButton.setChecked(!GeneralVariables.audioOutput32Bit);
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.audioBitsRadioGroup.getCheckedRadioButtonId();
                GeneralVariables.audioOutput32Bit = buttonId == binding.audio32BitsRadioButton.getId();
                writeConfig("audioBits", GeneralVariables.audioOutput32Bit ? "1" : "0");
            }
        };
        binding.audio32BitsRadioButton.setOnClickListener(listener);
        binding.audio16BitsRadioButton.setOnClickListener(listener);
    }

    /**
     * Set audio output sample rate
     */
    private void setAudioOutputRateMode() {
        binding.audioRateRadioGroup.clearCheck();
        binding.audio12kRadioButton.setChecked(GeneralVariables.audioSampleRate == 12000);
        binding.audio24kRadioButton.setChecked(GeneralVariables.audioSampleRate == 24000);
        binding.audio48kRadioButton.setChecked(GeneralVariables.audioSampleRate == 48000);
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (binding.audio12kRadioButton.isChecked()) GeneralVariables.audioSampleRate = 12000;
                if (binding.audio24kRadioButton.isChecked()) GeneralVariables.audioSampleRate = 24000;
                if (binding.audio48kRadioButton.isChecked()) GeneralVariables.audioSampleRate = 48000;
                writeConfig("audioRate", String.valueOf(GeneralVariables.audioSampleRate));
            }
        };
        binding.audio12kRadioButton.setOnClickListener(listener);
        binding.audio24kRadioButton.setOnClickListener(listener);
        binding.audio48kRadioButton.setOnClickListener(listener);
    }

    /**
     * Set message list display mode
     */
    private void setMessageMode() {
        binding.messageModeRadioGroup.clearCheck();
        if (GeneralVariables.simpleCallItemMode) {
            binding.msgSimpleRadioButton.setChecked(true);
        } else {
            binding.msgStandardRadioButton.setChecked(true);
        }
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.messageModeRadioGroup.getCheckedRadioButtonId();
                GeneralVariables.simpleCallItemMode = buttonId == binding.msgSimpleRadioButton.getId();
                writeConfig("msgMode", GeneralVariables.simpleCallItemMode ? "1" : "0");
            }
        };
        binding.msgStandardRadioButton.setOnClickListener(listener);
        binding.msgSimpleRadioButton.setOnClickListener(listener);
    }

    /**
     * Set control mode VOX CAT
     */
    private void setControlMode() {
        binding.controlModeRadioGroup.clearCheck();
        switch (GeneralVariables.controlMode) {
            case ControlMode.CAT:
            case ConnectMode.NETWORK:
                binding.ctrCATradioButton.setChecked(true);
                break;
            case ControlMode.RTS:
                binding.ctrRTSradioButton.setChecked(true);
                break;
            case ControlMode.DTR:
                binding.ctrDTRradioButton.setChecked(true);
                break;
            default:
                binding.ctrVOXradioButton.setChecked(true);
        }
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.controlModeRadioGroup.getCheckedRadioButtonId();
                if (buttonId == binding.ctrVOXradioButton.getId()) {
                    GeneralVariables.controlMode = ControlMode.VOX;
                } else if (buttonId == binding.ctrCATradioButton.getId()) {
                    GeneralVariables.controlMode = ControlMode.CAT;
                } else if (buttonId == binding.ctrRTSradioButton.getId()) {
                    GeneralVariables.controlMode = ControlMode.RTS;
                } else if (buttonId == binding.ctrDTRradioButton.getId()) {
                    GeneralVariables.controlMode = ControlMode.DTR;
                }
                mainViewModel.setControlMode();
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (!mainViewModel.isRigConnected()) {
                        mainViewModel.getUsbDevice();
                    } else {
                        mainViewModel.setOperationBand();
                    }
                }
                writeConfig("ctrMode", String.valueOf(GeneralVariables.controlMode));
                setConnectMode();
            }
        };
        binding.ctrCATradioButton.setOnClickListener(listener);
        binding.ctrVOXradioButton.setOnClickListener(listener);
        binding.ctrRTSradioButton.setOnClickListener(listener);
        binding.ctrDTRradioButton.setOnClickListener(listener);
    }

    /**
     * Set connection mode: USB or Bluetooth
     */
    private void setConnectMode() {
        if ((GeneralVariables.controlMode == ControlMode.CAT)) {
            binding.connectModeLayout.setVisibility(View.VISIBLE);
            binding.serialLayout.setVisibility(View.VISIBLE);
        } else {
            binding.connectModeLayout.setVisibility(View.GONE);
            binding.serialLayout.setVisibility(View.GONE);
        }

        // === Restore saved connect mode ===
        binding.connectModeRadioGroup.clearCheck();
        int savedConnectMode = GeneralVariables.connectMode;
        switch (savedConnectMode) {
            case ConnectMode.USB_CABLE:
                binding.cableConnectRadioButton.setChecked(true);
                break;
            case ConnectMode.BLUE_TOOTH:
                binding.bluetoothConnectRadioButton.setChecked(true);
                break;
            case ConnectMode.NETWORK:
                binding.networkConnectRadioButton.setChecked(true);
                break;
        }
        // ==================================

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.connectModeRadioGroup.getCheckedRadioButtonId();
                if (buttonId == binding.cableConnectRadioButton.getId()) {
                    GeneralVariables.connectMode = ConnectMode.USB_CABLE;
                } else if (buttonId == binding.bluetoothConnectRadioButton.getId()) {
                    GeneralVariables.connectMode = ConnectMode.BLUE_TOOTH;
                } else if (buttonId == binding.networkConnectRadioButton.getId()) {
                    GeneralVariables.connectMode = ConnectMode.NETWORK;
                }

                // === Save to database immediately ===
                writeConfig("connectMode", String.valueOf(GeneralVariables.connectMode));
                // ====================================

                if (GeneralVariables.connectMode == ConnectMode.BLUE_TOOTH) {
                    new SelectBluetoothDialog(requireContext(), mainViewModel).show();
                }
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                    if (GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK) {
                        new SelectFlexRadioDialog(requireContext(), mainViewModel).show();
                    } else if (GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS) {
                        new SelectXieguRadioDialog(requireContext(), mainViewModel).show();
                    } else if (GeneralVariables.instructionSet == InstructionSet.ICOM
                            || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100
                            || GeneralVariables.instructionSet == InstructionSet.XIEGUG90S) {
                        new LoginIcomRadioDialog(requireContext(), mainViewModel).show();
                    } else {
                        ToastMessage.show(GeneralVariables.getStringFromResource(R.string.only_flex_supported));
                    }
                }
            }
        };
        binding.cableConnectRadioButton.setOnClickListener(listener);
        binding.bluetoothConnectRadioButton.setOnClickListener(listener);
        binding.networkConnectRadioButton.setOnClickListener(listener);
    }

    /**
     * Write config to database
     * @param KeyName key
     * @param Value value
     */
    private void writeConfig(String KeyName, String Value) {
        mainViewModel.databaseOpr.writeConfig(KeyName, Value, null);
    }

    private void setHelpDialog() {
        // Callsign help
        binding.callsignHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.callsign_help)
                        , true).show();
            }
        });

        // Cloudlog help
        binding.cloudlogSettingsImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.cloudlog_help)
                        , true).show();
            }
        });

        // QRZ help
        binding.qrzSettingsImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.qrz_help)
                        , true).show();
            }
        });

        // HRDLog help
        binding.hrdlogSettingsImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , "HRDLog.net API integration:\n\n" +
                        "• API URL: https://api.hrdlog.net\n" +
                        "• Get API Key from your HRDLog profile\n" +
                        "• Username/Password: your HRDLog account\n" +
                        "• Callsign: your operating callsign", true).show();
            }
        });

        // Maidenhead grid help
        binding.maidenGridImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.maidenhead_help)
                        , true).show();
            }
        });

        // Transmit frequency help
        binding.frequencyImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.frequency_help)
                        , true).show();
            }
        });

        // Web port help - NEW
        binding.webPortHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.faq_web_port)
                        , true).show();
            }
        });

        // Clear Call Hist help
        binding.clearCallHistHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.faq_clear_call_hist_on_freq_change)
                        , true).show();
            }
        });

        // Transmit delay help
        binding.transDelayImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.transDelay_help)
                        , true).show();
            }
        });

        // Time offset help
        binding.timeOffsetImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.timeoffset_help)
                        , true).show();
            }
        });

        // PTT delay help
        binding.pttDelayImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.pttdelay_help)
                        , true).show();
            }
        });

        // Serial settings help
        binding.serialHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.serial_setting_help)
                        , true).show();
            }
        });

        // Message mode help
        binding.messageModeeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.message_mode_help)
                        , true).show();
            }
        });

        // Set ABOUT
        binding.aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity(), "readme.txt", true).show();
            }
        });

        // Operation band help
        binding.operationHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.operationBand_help)
                        , true).show();
            }
        });

        // Control mode help
        binding.controlModeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.controlMode_help)
                        , true).show();
            }
        });

        // CI-V address and baud rate help
        binding.baudRateHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.civ_help)
                        , true).show();
            }
        });

        // Rig model list help
        binding.rigNameHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.rig_model_help)
                        , true).show();
            }
        });

        // Transmit supervision help
        binding.launchSupervisionImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.launch_supervision_help)
                        , true).show();
            }
        });

        // No reply limit help
        binding.noResponseCountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.no_response_help)
                        , true).show();
            }
        });

        // Auto follow help
        binding.autoFollowCountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.auto_follow_help)
                        , true).show();
            }
        });

        // Connection mode help
        binding.connectModeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.connectMode_help)
                        , true).show();
            }
        });

        // Exclude callsign help
        binding.excludedHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.excludeCallsign_help)
                        , true).show();
            }
        });

        binding.swlHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.swlMode_help)
                        , true).show();
            }
        });

        // Decode mode help
        binding.decodeModeHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.deep_mode_help)
                        , true).show();
            }
        });

        // Audio output help
        binding.audioOutputImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.audio_output_help)
                        , true).show();
            }
        });

        // Clear cache help
        binding.clearCacheHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.clear_cache_data_help)
                        , true).show();
            }
        });

        // Cloudlog test
        binding.testCloudlogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.testCloudlogButton.setEnabled(false);
                binding.testCloudlogButton.setText(getResources().getString(R.string.testing));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean result = ThirdPartyService.CheckCloudlogConnection();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (result) {
                                    binding.testCloudlogButton.setText(getResources().getString(R.string.pass));
                                } else {
                                    binding.testCloudlogButton.setText(getResources().getString(R.string.fail));
                                }
                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        binding.testCloudlogButton.setEnabled(true);
                                        binding.testCloudlogButton.setText(getResources().getString(R.string.test));
                                    }
                                }, 3000);
                            }
                        });
                    }
                }).start();
            }
        });

        // QRZ test
        binding.testQrzButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.testQrzButton.setEnabled(false);
                binding.testQrzButton.setText(getResources().getString(R.string.testing));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean result = ThirdPartyService.CheckQRZConnection();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (result) {
                                    binding.testQrzButton.setText(getResources().getString(R.string.pass));
                                } else {
                                    binding.testQrzButton.setText(getResources().getString(R.string.fail));
                                }
                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        binding.testQrzButton.setEnabled(true);
                                        binding.testQrzButton.setText(getResources().getString(R.string.test));
                                    }
                                }, 3000);
                            }
                        });
                    }
                }).start();
            }
        });

        // HRDLog test
        binding.testHrdlogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.testHrdlogButton.setEnabled(false);
                binding.testHrdlogButton.setText("Testing...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean result = ThirdPartyService.CheckHrdlogConnection();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (result) {
                                    binding.testHrdlogButton.setText("OK");
                                    ToastMessage.show("HRDLog connection successful");
                                } else {
                                    binding.testHrdlogButton.setText("Fail");
                                    ToastMessage.show("HRDLog connection failed");
                                }
                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        binding.testHrdlogButton.setEnabled(true);
                                        binding.testHrdlogButton.setText("Test");
                                    }
                                }, 2000);
                            }
                        });
                    }
                }).start();
            }
        });

        binding.clearFollowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new ClearCacheDataDialog(requireContext(), requireActivity()
                        , mainViewModel.databaseOpr
                        , ClearCacheDataDialog.CACHE_MODE.FOLLOW_DATA).show();
            }
        });

        binding.clearLogCacheButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new ClearCacheDataDialog(requireContext(), requireActivity()
                        , mainViewModel.databaseOpr
                        , ClearCacheDataDialog.CACHE_MODE.SWL_MSG).show();
            }
        });

        binding.clearSWlQsoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new ClearCacheDataDialog(requireContext(), requireActivity()
                        , mainViewModel.databaseOpr
                        , ClearCacheDataDialog.CACHE_MODE.SWL_QSO).show();
            }
        });

        // Delete shared temp files
        binding.clearShareDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        GeneralVariables.clearCache(binding.getRoot().getContext());
                    }
                }).start();
            }
        });

        binding.synTImeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                UtcTimer.syncTime(new UtcTimer.AfterSyncTime() {
                    @Override
                    public void doAfterSyncTimer(int secTime) {
                        requireActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setUtcTimeOffsetSpinner();
                                updateOffsetDisplay();
                            }
                        });
                        if (secTime > 100) {
                            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.utc_time_sync_delay_slow), secTime));
                        } else if (secTime < -100) {
                            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.utc_time_sync_delay_faster), -secTime));
                        } else {
                            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.config_clock_is_accurate));
                        }

                    }
                    @Override
                    public void syncFailed(IOException e) {
                        ToastMessage.show(e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Set scroll icons for settings interface
     */
    private void setScrollImageVisible() {
        if (binding.scrollView3.getScrollY() == 0) {
            binding.configScrollUpImageView.setVisibility(View.GONE);
        } else {
            binding.configScrollUpImageView.setVisibility(View.VISIBLE);
        }
        if (binding.scrollView3.getHeight() + binding.scrollView3.getScrollY()
                < binding.scrollLinearLayout.getMeasuredHeight()) {
            binding.configScrollDownImageView.setVisibility(View.VISIBLE);
        } else {
            binding.configScrollDownImageView.setVisibility(View.GONE);
        }
    }

    /**
     * Updates the offset display text.
     * Logic:
     * - If spinner shows 0.0 sec (auto mode) - show exact calculated offset from UtcTimer
     * - If spinner shows other value - show fixed value that was selected
     * Update only outside transmit intervals
     */
    private void updateOffsetDisplay() {
        if (binding.utcCalculatedOffsetText == null) return;

        if (mainViewModel != null && mainViewModel.ft8TransmitSignal != null
                && mainViewModel.ft8TransmitSignal.isTransmitting()) {
            return;
        }

        long displayOffset;
        if (isAutoOffsetMode) {
            displayOffset = UtcTimer.delay;
        } else {
            displayOffset = UtcTimer.delay;
        }

        binding.utcCalculatedOffsetText.setText(String.format("%+d ms", displayOffset));

        if (isAutoOffsetMode) {
            int color = Math.abs(displayOffset) <= 100
                    ? requireContext().getColor(R.color.text_view_color)
                    : requireContext().getColor(R.color.text_view_error_color);
            binding.utcCalculatedOffsetText.setTextColor(color);
        } else {
            binding.utcCalculatedOffsetText.setTextColor(requireContext().getColor(R.color.text_view_color));
        }
    }

}