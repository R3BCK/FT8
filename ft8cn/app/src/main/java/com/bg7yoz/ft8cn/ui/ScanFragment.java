//ScanFragment.java

package com.bg7yoz.ft8cn.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ScanFragment extends Fragment {
    private static final String TAG = "ScanFragment";

    private MainViewModel mainViewModel;
    private NavController navController;
    private Button btnStartStop;
    private ImageView btnClearTable;
    private EditText etScanCycles;
    private LinearLayout containerScanContent;
    private TextView tvTotalAll, tvTotalNew, tvUtcTime, tvUtcDelay, tvRfFreq, tvConnStatus, tvRigType;
    private CheckBox cbHeaderHide, cbHeaderSelect;

    private Handler scanHandler, utcDelayHandler;
    private boolean isScanning = false;
    private int scanCycles = 5;
    private int currentFreqIndex = 0;

    private boolean wasListenerPaused = false;
    private final Object freqSwitchLock = new Object();
    private final List<Long> pendingScanFrequencies = new ArrayList<>();
    private final List<Long> activeScanFrequencies = new ArrayList<>();
    private final Object scanListLock = new Object();

    // Simple collection: RF frequency -> unique callsigns heard on it
    private final Map<Long, Set<String>> freqCallsigns = new HashMap<>();
    private long currentScanFreq = -1;

    private Observer<ArrayList<Ft8Message>> scanMessageObserver;
    private Observer<Long> scanTimerObserver;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mainViewModel = MainViewModel.getInstance(this);

        NavHostFragment navHostFragment = (NavHostFragment) requireActivity()
                .getSupportFragmentManager()
                .findFragmentById(R.id.fragmentContainerView);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        scanHandler = new Handler(Looper.getMainLooper());
        utcDelayHandler = new Handler(Looper.getMainLooper());

        // Bind UI components
        btnStartStop = view.findViewById(R.id.btnScanStartStop);
        btnClearTable = view.findViewById(R.id.btnClearTable);
        etScanCycles = view.findViewById(R.id.etDwellCycles);
        containerScanContent = view.findViewById(R.id.containerScanContent);
        tvTotalAll = view.findViewById(R.id.tvTotalAll);
        tvTotalNew = view.findViewById(R.id.tvTotalNew);
        tvUtcTime = view.findViewById(R.id.tvUtcTime);
        tvUtcDelay = view.findViewById(R.id.tvUtcDelay);
        tvRfFreq = view.findViewById(R.id.tvRfFreq);
        tvConnStatus = view.findViewById(R.id.tvConnStatus);
        tvRigType = view.findViewById(R.id.tvRigType);
        cbHeaderHide = view.findViewById(R.id.cbHeaderHide);
        cbHeaderSelect = view.findViewById(R.id.cbHeaderSelect);

        // Disable header GO button programmatically (safety)
        Button btnHeaderGo = view.findViewById(R.id.btnHeaderGo);
        if (btnHeaderGo != null) {
            btnHeaderGo.setEnabled(false);
            btnHeaderGo.setClickable(false);
            btnHeaderGo.setFocusable(false);
        }

        // [FIX] Do NOT pause decoder - we need it running to decode messages during scan
        // pauseGlobalListenerIfNeeded(); <-- REMOVED

        btnStartStop.setOnClickListener(v -> {
            synchronized (freqSwitchLock) {
                if (!isScanning) {
                    if (mainViewModel == null || mainViewModel.baseRig == null || !mainViewModel.baseRig.isConnected()) {
                        Toast.makeText(getContext(), "Rig not connected", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    isScanning = true;
                    btnStartStop.setText("Stop");
                    scanCycles = parseScanCycles();
                    startRealScan();
                    saveScanState();
                } else {
                    isScanning = false;
                    btnStartStop.setText("Start");
                    stopScan();
                    saveScanState();
                }
            }
        });

        btnClearTable.setOnClickListener(v -> {
            resetRowCounters();
            synchronized (this) {
                freqCallsigns.clear();
                currentScanFreq = -1;
            }
            if (tvTotalAll != null) tvTotalAll.setText("0");
            if (tvTotalNew != null) tvTotalNew.setText("0");
            saveScanState();
            Toast.makeText(getContext(), "Counters reset", Toast.LENGTH_SHORT).show();
        });

        // Header checkboxes - updated with applyPendingScanList()
        cbHeaderHide.setOnClickListener(v -> {
            showAllFrequencies(cbHeaderHide.isChecked());
            updatePendingScanList();
            applyPendingScanList();
            saveScanState();
        });
        cbHeaderSelect.setOnClickListener(v -> {
            selectAllFrequencies(cbHeaderSelect.isChecked());
            updatePendingScanList();
            applyPendingScanList();
            saveScanState();
        });

        etScanCycles.setOnEditorActionListener((v, actionId, event) -> {
            scanCycles = parseScanCycles();
            saveScanState();
            return true;
        });

        mainViewModel.timerSec.observe(getViewLifecycleOwner(), aLong -> {
            if (tvUtcTime != null) tvUtcTime.setText(UtcTimer.getTimeStr(aLong));
        });

        utcDelayHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tvUtcDelay != null && getActivity() != null) {
                    tvUtcDelay.setText(String.format(Locale.US, "%+d", UtcTimer.delay));
                    utcDelayHandler.postDelayed(this, 500);
                }
            }
        }, 500);

        updateRigStatus();
        tvRfFreq.setText(String.valueOf(GeneralVariables.band));

        populateFrequencyTable();
        restoreScanState();

        if (etScanCycles.getText().toString().isEmpty()) {
            etScanCycles.setText(String.valueOf(scanCycles));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Do not pause listener on resume - decoder must keep running
    }

    @Override
    public void onPause() {
        super.onPause();
        saveScanState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        saveScanState();

        if (isScanning) {
            isScanning = false;
            stopScan();
        }

        // Resume global listener for other fragments if it was paused elsewhere
        if (wasListenerPaused && mainViewModel != null && mainViewModel.ft8SignalListener != null) {
            mainViewModel.ft8SignalListener.startListen();
            wasListenerPaused = false;
        }

        synchronized (freqSwitchLock) {
            if (scanMessageObserver != null && mainViewModel != null) {
                try {
                    mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver);
                } catch (Exception ignored) {}
                scanMessageObserver = null;
            }
            if (scanHandler != null) scanHandler.removeCallbacksAndMessages(null);
        }
    }

    // === [DATABASE] State management via SQLite ===
    private void saveScanState() {
        if (mainViewModel == null || mainViewModel.databaseOpr == null) return;
        mainViewModel.databaseOpr.writeConfig("scan_cycles", String.valueOf(scanCycles), null);

        StringBuilder stateSb = new StringBuilder();
        if (containerScanContent != null) {
            for (int i = 0; i < containerScanContent.getChildCount(); i++) {
                View row = containerScanContent.getChildAt(i);
                Long freq = (Long) row.getTag();
                if (freq != null) {
                    CheckBox cbSel = row.findViewById(R.id.cbRowSelect);
                    CheckBox cbHide = row.findViewById(R.id.cbRowHide);
                    if (stateSb.length() > 0) stateSb.append(";");
                    stateSb.append(freq).append(":")
                            .append(cbSel != null && cbSel.isChecked() ? 1 : 0).append(",")
                            .append(cbHide != null && cbHide.isChecked() ? 1 : 0);
                }
            }
        }
        mainViewModel.databaseOpr.writeConfig("scan_row_states", stateSb.toString(), null);
    }

    private void restoreScanState() {
        if (containerScanContent == null || containerScanContent.getChildCount() == 0 || mainViewModel.databaseOpr == null) return;

        String cyclesStr = mainViewModel.databaseOpr.readConfig("scan_cycles", "5");
        try { scanCycles = Integer.parseInt(cyclesStr); } catch (Exception e) { scanCycles = 5; }
        if (etScanCycles != null) etScanCycles.setText(String.valueOf(scanCycles));

        String statesStr = mainViewModel.databaseOpr.readConfig("scan_row_states", "");
        if (!statesStr.isEmpty()) {
            String[] entries = statesStr.split(";");
            for (String entry : entries) {
                String[] parts = entry.split(":");
                if (parts.length == 2) {
                    try {
                        long freq = Long.parseLong(parts[0]);
                        String[] states = parts[1].split(",");
                        if (states.length == 2) {
                            boolean sel = "1".equals(states[0]);
                            boolean hide = "1".equals(states[1]);
                            for (int i = 0; i < containerScanContent.getChildCount(); i++) {
                                View row = containerScanContent.getChildAt(i);
                                if (freq == (Long) row.getTag()) {
                                    CheckBox cbSel = row.findViewById(R.id.cbRowSelect);
                                    CheckBox cbHide = row.findViewById(R.id.cbRowHide);
                                    if (cbSel != null) cbSel.setChecked(sel);
                                    if (cbHide != null) {
                                        cbHide.setChecked(hide);
                                        row.setVisibility(hide ? View.VISIBLE : View.GONE);
                                    }
                                    break;
                                }
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        updatePendingScanList();
        initScanLists();
    }

    private void resetRowCounters() {
        if (containerScanContent == null) return;
        for (int i = 0; i < containerScanContent.getChildCount(); i++) {
            View row = containerScanContent.getChildAt(i);
            for (int id : new int[]{R.id.tvRowTot, R.id.tvRowNew, R.id.tvRowD, R.id.tvRowC, R.id.tvRowI}) {
                TextView tv = row.findViewById(id);
                if (tv != null) tv.setText("0");
            }
        }
        synchronized (this) { freqCallsigns.clear(); }
    }

    private int parseScanCycles() {
        try {
            int val = Integer.parseInt(etScanCycles.getText().toString().trim());
            return Math.max(1, Math.min(10, val));
        } catch (Exception e) { return 5; }
    }

    private void updateRigStatus() {
        if (tvConnStatus == null || tvRigType == null) return;
        if (mainViewModel != null && mainViewModel.baseRig != null) {
            try {
                boolean connected = mainViewModel.baseRig.isConnected();
                if (connected) {
                    tvConnStatus.setText("Connected");
                    tvConnStatus.setTextColor(getResources().getColor(R.color.is_qsl_text_color, null));
                    tvRigType.setText(mainViewModel.baseRig.getClass().getSimpleName());
                } else {
                    tvConnStatus.setText("Disconnected");
                    tvConnStatus.setTextColor(getResources().getColor(R.color.text_view_error_color, null));
                    tvRigType.setText("No Rig");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking rig status: " + e.getMessage());
                tvConnStatus.setText("Error");
            }
        } else {
            tvConnStatus.setText("Disconnected");
            tvConnStatus.setTextColor(getResources().getColor(R.color.text_view_error_color, null));
            tvRigType.setText("No Rig");
        }
    }

    private void populateFrequencyTable() {
        if (containerScanContent == null) return;
        containerScanContent.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int i = 0; i < 20; i++) {
            try {
                long RFfreq = OperationBand.getBandFreq(i);
                if (RFfreq <= 0) continue;
                View rowView = inflater.inflate(R.layout.item_scan_row, containerScanContent, false);
                bindRowData(rowView, String.valueOf(RFfreq), RFfreq, true, true);
                containerScanContent.addView(rowView);
            } catch (Exception e) { break; }
        }
        initScanLists();
    }

    private void initScanLists() {
        List<Long> initial = getSelectedFrequencies();
        synchronized (scanListLock) {
            pendingScanFrequencies.clear(); pendingScanFrequencies.addAll(initial);
            activeScanFrequencies.clear(); activeScanFrequencies.addAll(initial);
        }
    }

    private void updatePendingScanList() {
        List<Long> newList = getSelectedFrequencies();
        synchronized (scanListLock) {
            pendingScanFrequencies.clear(); pendingScanFrequencies.addAll(newList);
        }
    }

    private void applyPendingScanList() {
        synchronized (scanListLock) {
            activeScanFrequencies.clear();
            activeScanFrequencies.addAll(pendingScanFrequencies);
        }
    }

    private void bindRowData(View rowView, String label, long RFfreq, boolean visible, boolean selected) {
        rowView.setVisibility(visible ? View.VISIBLE : View.GONE);
        rowView.setTag(RFfreq);

        CheckBox cbHide = rowView.findViewById(R.id.cbRowHide);
        cbHide.setChecked(visible);
        cbHide.setOnClickListener(v -> {
            boolean isChecked = cbHide.isChecked();
            rowView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (!isChecked) {
                CheckBox cbSelect = rowView.findViewById(R.id.cbRowSelect);
                cbSelect.setChecked(false);
            }
            updatePendingScanList(); applyPendingScanList(); saveScanState();
        });

        CheckBox cbSelect = rowView.findViewById(R.id.cbRowSelect);
        cbSelect.setChecked(selected);
        cbSelect.setOnClickListener(v -> { updatePendingScanList(); applyPendingScanList(); saveScanState(); });

        TextView tvFreq = rowView.findViewById(R.id.tvRowFreq);
        tvFreq.setText(label);

        Button btnGo = rowView.findViewById(R.id.btnRowGo);
        if ("Go".equals(btnGo.getText().toString())) btnGo.setText("Switch");
        btnGo.setOnClickListener(v -> safeSwitchToFrequencyAndNavigate(RFfreq));
    }

    private void switchToFrequency(long RFfreq) {
        synchronized (freqSwitchLock) {
            if (mainViewModel != null && mainViewModel.baseRig != null && mainViewModel.baseRig.isConnected()) {
                try {
                    Log.d(TAG, "switchToFrequency: FINAL COMMAND TO RIG -> RFfreq=" + RFfreq);
                    GeneralVariables.band = RFfreq;
                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(RFfreq);
                    GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
                    mainViewModel.setOperationBand();
                    if (tvRfFreq != null) tvRfFreq.setText(String.valueOf(RFfreq));
                    updateRigStatus();
                    Toast.makeText(getContext(), "Switched to " + RFfreq, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Error switching frequency: " + e.getMessage());
                }
            }
        }
    }

    private void safeSwitchToFrequencyAndNavigate(long RFfreq) {
        Log.d(TAG, ">>> ENTRY safeSwitchToFrequencyAndNavigate: RFfreq=" + RFfreq);
        if (isScanning) {
            isScanning = false;
            if (btnStartStop != null) btnStartStop.setText("Start");
            stopScan();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                switchToFrequency(RFfreq);
                if (navController != null) navController.navigate(R.id.menu_nav_mycalling);
            }, 100);
        } else {
            switchToFrequency(RFfreq);
            if (navController != null) navController.navigate(R.id.menu_nav_mycalling);
        }
    }

    // Loads worked callsigns synchronously without using package-private DatabaseOpr classes
    private void loadWorkedCallsigns() {
        if (mainViewModel == null || mainViewModel.databaseOpr == null) return;
        android.database.sqlite.SQLiteDatabase db = mainViewModel.databaseOpr.getDb();
        if (db == null) return;

        String bandStr = BaseRigOperation.getMeterFromFreq(GeneralVariables.band);

        // Query for current band
        String querySQL = "select distinct [call] from QSLTable where band=?";
        android.database.Cursor cursor = db.rawQuery(querySQL, new String[]{bandStr});
        ArrayList<String> callsigns = new ArrayList<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String s = cursor.getString(cursor.getColumnIndex("call"));
                if (s != null) callsigns.add(s);
            }
            cursor.close();
        }
        GeneralVariables.QSL_Callsign_list = callsigns;

        // Query for other bands
        querySQL = "select distinct [call] from QSLTable where band<>?";
        cursor = db.rawQuery(querySQL, new String[]{bandStr});
        ArrayList<String> other_callsigns = new ArrayList<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String s = cursor.getString(cursor.getColumnIndex("call"));
                if (s != null) other_callsigns.add(s);
            }
            cursor.close();
        }
        GeneralVariables.QSL_Callsign_list_other_band = other_callsigns;
    }

    private void startRealScan() {
        if (containerScanContent == null) return;
        if (mainViewModel == null || mainViewModel.ft8SignalListener == null) {
            Log.e(TAG, "Cannot start scan: listener not ready"); return;
        }

        // [FIX] Decoder stays running - messages flow to mutableFt8MessageList automatically

        currentFreqIndex = 0;
        synchronized (this) {
            freqCallsigns.clear();
            currentScanFreq = -1;
        }
        resetRowCounters();
        if (tvTotalAll != null) tvTotalAll.setText("0");
        if (tvTotalNew != null) tvTotalNew.setText("0");

        // Load worked callsigns like Decode does
        if (mainViewModel.databaseOpr != null) {
            loadWorkedCallsigns();
        }

        List<Long> initialFreqs;
        synchronized (scanListLock) { initialFreqs = new ArrayList<>(activeScanFrequencies); }
        if (initialFreqs.isEmpty()) {
            Toast.makeText(getContext(), "No frequencies selected", Toast.LENGTH_SHORT).show();
            isScanning = false; if (btnStartStop != null) btnStartStop.setText("Start"); return;
        }

        Log.d(TAG, "startRealScan: === STARTING SCAN ===");

        // [FIX] Create observer ONCE at the start of scan
        scanMessageObserver = messages -> {
            if (!isScanning || messages == null) return;

            // Get current scan frequency under lock
            long targetFreq;
            synchronized (this) {
                targetFreq = currentScanFreq;
            }

            // Skip if no target frequency set yet
            if (targetFreq < 0) return;

            // [DEBUG] Log that we received messages
            Log.d(TAG, "scanMessageObserver: RECEIVED " + messages.size() +
                    " messages on freq " + targetFreq);

            try {
                for (Ft8Message msg : messages) {
                    String callsign = msg.getCallsignFrom();
                    if (callsign != null && !callsign.isEmpty()) {
                        // [FIX] Calculate actual RF frequency of this message
                        // msg.freq_hz is offset from GeneralVariables.band
                        long msgRfFreq = GeneralVariables.band + Math.round(msg.freq_hz);

                        // [FIX] Only count messages that match current scan frequency (±3 kHz tolerance for FT8)
                        if (Math.abs(msgRfFreq - targetFreq) <= 3000) {
                            String upperCall = callsign.toUpperCase();
                            synchronized (this) {
                                freqCallsigns.computeIfAbsent(targetFreq, k -> new HashSet<>())
                                        .add(upperCall);
                            }
                            Log.d(TAG, "scanMessageObserver: Added callsign " + upperCall +
                                    " to freq " + targetFreq +
                                    " (total now: " + freqCallsigns.get(targetFreq).size() + ")");
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing scan messages: " + e.getMessage());
            }
        };

        // [FIX] Attach observer ONCE for entire scan session
        if (mainViewModel != null) {
            try {
                mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver);
            } catch (Exception ignored) {}
            mainViewModel.mutableFt8MessageList.observe(getViewLifecycleOwner(), scanMessageObserver);
            Log.d(TAG, "startRealScan: Observer attached for entire scan session");
        }

        scanNextFrequency(initialFreqs);
    }

    private ArrayList<Long> getSelectedFrequencies() {
        ArrayList<Long> freqs = new ArrayList<>();
        if (containerScanContent == null) return freqs;
        for (int i = 0; i < containerScanContent.getChildCount(); i++) {
            View row = containerScanContent.getChildAt(i);
            CheckBox cb = row.findViewById(R.id.cbRowSelect);
            if (cb.isChecked() && row.getVisibility() == View.VISIBLE) {
                Long f = (Long) row.getTag();
                if (f != null) freqs.add(f);
            }
        }
        return freqs;
    }

    private void scanNextFrequency(List<Long> currentList) {
        synchronized (freqSwitchLock) {
            if (!isScanning || mainViewModel == null) {
                stopScan();
                Toast.makeText(getContext(), "Scan complete", Toast.LENGTH_SHORT).show();
                return;
            }

            if (currentFreqIndex >= currentList.size()) {
                Log.d(TAG, "scanNextFrequency: All frequencies scanned. Calculating stats...");
                postScanCalculateAndShow();
                return;
            }

            long RFfreq = currentList.get(currentFreqIndex);
            Log.d(TAG, "scanNextFrequency: Selected RF freq=" + RFfreq);

            if (mainViewModel.baseRig == null || !mainViewModel.baseRig.isConnected()) {
                Log.e(TAG, "Rig disconnected during scan");
                Toast.makeText(getContext(), "Rig disconnected", Toast.LENGTH_SHORT).show();
                stopScan(); return;
            }

            try {
                GeneralVariables.band = RFfreq;
                GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(RFfreq);
                GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);

                if (mainViewModel.baseRig != null && mainViewModel.baseRig.isConnected()) {
                    mainViewModel.baseRig.setUsbModeToRig();
                    mainViewModel.baseRig.setFreq(RFfreq);
                    mainViewModel.baseRig.setFreqToRig();
                }
                if (tvRfFreq != null) tvRfFreq.setText(String.valueOf(RFfreq));
            } catch (Exception e) {
                Log.e(TAG, "Error setting band: " + e.getMessage());
                stopScan(); return;
            }

            // [FIX] Update currentScanFreq BEFORE starting to listen
            // This ensures observer filters messages correctly for new frequency
            synchronized (this) {
                currentScanFreq = RFfreq;
                // Ensure HashSet exists for this frequency (will be empty at start)
                freqCallsigns.putIfAbsent(RFfreq, new HashSet<>());
            }

            // [FIX] Do NOT re-attach observer here - it was attached once in startRealScan()
            // The observer stays active and filters by currentScanFreq

            Log.d(TAG, "scanNextFrequency: Starting listen cycle immediately for " + RFfreq);
            if (isScanning) {
                listenForMessages(RFfreq, scanCycles, currentList);
            }
        }
    }

    private void listenForMessages(long RFfreq, int cycles, List<Long> currentList) {
        if (scanHandler == null || !isScanning) return;
        final int[] slotsProcessed = {0};
        final int startSequential = UtcTimer.getNowSequential();

        scanTimerObserver = utcMillis -> {
            if (!isScanning) { mainViewModel.timerSec.removeObserver(scanTimerObserver); return; }
            int currentSequential = UtcTimer.sequential(utcMillis);
            int expectedSequential = (startSequential + slotsProcessed[0]) % 2;
            if (currentSequential != expectedSequential) return;

            slotsProcessed[0]++;
            Log.d(TAG, "listenForMessages: Slot " + slotsProcessed[0] + "/" + cycles + " completed on " + RFfreq);

            if (slotsProcessed[0] >= cycles) {
                mainViewModel.timerSec.removeObserver(scanTimerObserver);
                // [FIX] Do NOT remove scanMessageObserver here - it stays active for next frequency
                currentFreqIndex++;
                scanNextFrequency(currentList);
            }
        };
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), scanTimerObserver);
    }

    // Post-scan calculation using Decode infrastructure (World Model + QSL list)
    private void postScanCalculateAndShow() {
        new Thread(() -> {
            Map<Long, int[]> freqStats = new HashMap<>();
            synchronized (this) {
                Log.d(TAG, "postScanCalculateAndShow: Processing " + freqCallsigns.size() + " frequencies");
                for (Map.Entry<Long, Set<String>> entry : freqCallsigns.entrySet()) {
                    long freq = entry.getKey();
                    Set<String> calls = entry.getValue();
                    int total = calls.size();
                    int newCount = 0;
                    Set<String> dxcc = new HashSet<>();
                    Set<Integer> itu = new HashSet<>();
                    Set<Integer> cq = new HashSet<>();

                    Log.d(TAG, "postScanCalculateAndShow: Freq " + freq + " has " + total + " callsigns");

                    for (String call : calls) {
                        // Check new against worked list
                        if (GeneralVariables.QSL_Callsign_list == null ||
                                !GeneralVariables.QSL_Callsign_list.contains(call)) {
                            newCount++;
                        }
                        // Pull stats from World Model (populated by Decode pipeline)
                        DatabaseOpr.StationRecord rec = DatabaseOpr.getStationRecord(call);
                        if (rec != null) {
                            Log.d(TAG, "postScanCalculateAndShow: Found record for " + call +
                                    " dxcc=" + rec.dxccCode + " itu=" + rec.lastItuZone +
                                    " cq=" + rec.lastCqZone);
                            if (rec.dxccCode != null && !rec.dxccCode.isEmpty())
                                dxcc.add(rec.dxccCode);
                            if (rec.lastItuZone > 0) itu.add(rec.lastItuZone);
                            if (rec.lastCqZone > 0) cq.add(rec.lastCqZone);
                        } else {
                            Log.w(TAG, "postScanCalculateAndShow: NO record for " + call +
                                    " in World Model!");
                        }
                    }
                    freqStats.put(freq, new int[]{total, newCount, dxcc.size(), cq.size(), itu.size()});
                    Log.d(TAG, "postScanCalculateAndShow: Freq " + freq + " stats: total=" + total +
                            ", new=" + newCount + ", dxcc=" + dxcc.size() +
                            ", itu=" + itu.size() + ", cq=" + cq.size());
                }
            }

            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                Log.d(TAG, "postScanCalculateAndShow: Updating UI with " + freqStats.size() + " frequency stats");
                for (Map.Entry<Long, int[]> e : freqStats.entrySet()) {
                    updateRowWithStats(e.getKey(), e.getValue());
                }
                updateTotalsFromScan(freqStats);
                saveScanState();
                stopScan();
                Toast.makeText(getContext(), "Scan complete", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void updateRowWithStats(long RFfreq, int[] stats) {
        if (containerScanContent == null) return;
        Log.d(TAG, "updateRowWithStats: Updating row for freq " + RFfreq +
                " with stats: total=" + stats[0] + ", new=" + stats[1] +
                ", dxcc=" + stats[2] + ", cq=" + stats[3] + ", itu=" + stats[4]);

        for (int i = 0; i < containerScanContent.getChildCount(); i++) {
            View row = containerScanContent.getChildAt(i);
            Object tag = row.getTag();
            if (tag instanceof Long && (Long) tag == RFfreq) {
                TextView tvTot = row.findViewById(R.id.tvRowTot);
                TextView tvNew = row.findViewById(R.id.tvRowNew);
                TextView tvD = row.findViewById(R.id.tvRowD);
                TextView tvC = row.findViewById(R.id.tvRowC);
                TextView tvI = row.findViewById(R.id.tvRowI);
                if (tvTot != null) tvTot.setText(String.valueOf(stats[0]));
                if (tvNew != null) tvNew.setText(String.valueOf(stats[1]));
                if (tvD != null) tvD.setText(String.valueOf(stats[2]));
                if (tvC != null) tvC.setText(String.valueOf(stats[3]));
                if (tvI != null) tvI.setText(String.valueOf(stats[4]));
                Log.d(TAG, "updateRowWithStats: Successfully updated UI for freq " + RFfreq);
                break;
            }
        }
    }

    private void updateTotalsFromScan(Map<Long, int[]> statsMap) {
        int sumTot = 0, sumNew = 0;
        for (int[] s : statsMap.values()) { sumTot += s[0]; sumNew += s[1]; }
        if (tvTotalAll != null) tvTotalAll.setText(String.valueOf(sumTot));
        if (tvTotalNew != null) tvTotalNew.setText(String.valueOf(sumNew));
        Log.d(TAG, "updateTotalsFromScan: Total stations=" + sumTot + ", new=" + sumNew);
    }

    private void stopScan() {
        synchronized (freqSwitchLock) {
            if (!isScanning) return;
            isScanning = false;
            // [FIX] Remove observer only when scan fully stops
            if (scanMessageObserver != null && mainViewModel != null) {
                try { mainViewModel.mutableFt8MessageList.removeObserver(scanMessageObserver); } catch (Exception ignored) {}
                scanMessageObserver = null;
            }
            if (scanHandler != null) scanHandler.removeCallbacksAndMessages(null);
            if (btnStartStop != null) btnStartStop.setText("Start");
        }
    }

    private void showAllFrequencies(boolean show) {
        if (containerScanContent == null) return;
        for (int j = 0; j < containerScanContent.getChildCount(); j++) {
            View row = containerScanContent.getChildAt(j);
            CheckBox cbHide = row.findViewById(R.id.cbRowHide);
            CheckBox cbSelect = row.findViewById(R.id.cbRowSelect);
            cbHide.setChecked(show);
            row.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) cbSelect.setChecked(false);
        }
    }

    private void selectAllFrequencies(boolean select) {
        if (containerScanContent == null) return;
        for (int j = 0; j < containerScanContent.getChildCount(); j++) {
            CheckBox cb = containerScanContent.getChildAt(j).findViewById(R.id.cbRowSelect);
            cb.setChecked(select);
        }
    }
}