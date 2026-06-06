//ScanFragment.java

package com.bg7yoz.ft8cn.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
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

    // [NEW] Static persistent cache: survives fragment recreation
    private static final Map<Long, int[]> STATIC_FREQ_STATS_CACHE = new HashMap<>();
    private static boolean STATIC_SCAN_COMPLETED = false;

    private MainViewModel mainViewModel;
    private NavController navController;
    private Button btnStartStop;
    private ImageView btnClearTable;
    private EditText etScanCycles;
    private LinearLayout containerScanContent;
    private TextView tvTotalAll, tvTotalNew, tvUtcTime, tvUtcDelay, tvRfFreq, tvConnStatus, tvRigType;
    private TextView tvScanSlotStatus;
    private CheckBox cbHeaderHide, cbHeaderSelect;
    // [NEW] Replaced Continuous checkbox with Post-Scan Action Spinner
    private Spinner spPostScanAction;

    // [NEW] Post-scan action constants
    private static final int ACTION_STOP = 0;
    private static final int ACTION_CONTINUOUS = 1;
    private static final int ACTION_SWITCH_MAX_NEW = 2;
    private static final int ACTION_SWITCH_MAX_DX = 3;
    private static final int ACTION_SWITCH_MAX_ITU = 4;
    private static final int ACTION_SWITCH_MAX_CQ = 5; // CQ Zone used for "Country" mapping
    private int selectedPostScanAction = ACTION_STOP;

    private Handler scanHandler, utcDelayHandler;
    private boolean isScanning = false;
    private boolean isScanPaused = false;
    private int scanCycles = 5;
    private int currentFreqIndex = 0;
    private int currentCycle = 1;

    // [NEW] State for pause/resume
    private int pausedFreqIndex = -1;
    private int pausedSlotCount = 0;
    private int pausedCycles = 0;
    private List<Long> pausedFreqList = null;

    private boolean wasListenerPaused = false;
    private final Object freqSwitchLock = new Object();
    private final List<Long> pendingScanFrequencies = new ArrayList<>();
    private final List<Long> activeScanFrequencies = new ArrayList<>();
    private final Object scanListLock = new Object();

    // Temporary working data (cleared on view destroy)
    private final Map<Long, Set<String>> freqCallsigns = new HashMap<>();

    private long currentScanFreq = -1;
    private int lastProcessedMessageIndex = 0;

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
        // [NEW] Bind Post-Scan Action Spinner
        spPostScanAction = view.findViewById(R.id.spPostScanAction);
        tvScanSlotStatus = view.findViewById(R.id.tvScanSlotStatus);

        // [NEW] Setup Spinner Adapter and Listener
        if (spPostScanAction != null) {
            String[] actions = {"Stop", "Continuous", "Switch to max New", "Switch to max DX", "Switch to max ITU", "Switch to max CQ"};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, actions);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spPostScanAction.setAdapter(adapter);
            spPostScanAction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedPostScanAction = position;
                    Log.d(TAG, "Post-scan action selected: " + position);
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

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
                if (isScanPaused) {
                    // [NEW] Continue paused scan
                    continueScan();
                } else if (!isScanning) {
                    // Start new scan
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
                    // Stop scan
                    isScanning = false;
                    isScanPaused = false;
                    btnStartStop.setText("Start");
                    stopScan();
                    saveScanState();
                }
            }
        });

        btnClearTable.setOnClickListener(v -> {
            // [FIX] Clear both temporary data AND static persistent cache
            resetRowCounters();
            synchronized (this) {
                freqCallsigns.clear();
                currentScanFreq = -1;
                lastProcessedMessageIndex = 0;
            }
            // Clear static cache
            synchronized (STATIC_FREQ_STATS_CACHE) {
                STATIC_FREQ_STATS_CACHE.clear();
                STATIC_SCAN_COMPLETED = false;
                currentCycle = 1;
            }
            updateHeaderTotalsFromCache();
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

        // [FIX] Restore UI from STATIC cache if ANY data exists
        synchronized (STATIC_FREQ_STATS_CACHE) {
            if (!STATIC_FREQ_STATS_CACHE.isEmpty()) {
                restoreUiFromStaticCache();
            }
        }

        if (etScanCycles.getText().toString().isEmpty()) {
            etScanCycles.setText(String.valueOf(scanCycles));
        }
    }

    // [NEW] Restore table UI from static persistent cache
    private void restoreUiFromStaticCache() {
        if (containerScanContent == null) return;

        synchronized (STATIC_FREQ_STATS_CACHE) {
            if (STATIC_FREQ_STATS_CACHE.isEmpty()) return;

            Log.d(TAG, "restoreUiFromStaticCache: Restoring " + STATIC_FREQ_STATS_CACHE.size() + " frequency rows");

            for (Map.Entry<Long, int[]> entry : STATIC_FREQ_STATS_CACHE.entrySet()) {
                long freq = entry.getKey();
                int[] stats = entry.getValue();
                updateRowWithStats(freq, stats);
            }

            updateHeaderTotalsFromCache();
            Log.d(TAG, "restoreUiFromStaticCache: UI restored successfully");
        }
    }

    // [NEW] Update header totals from persistent cache
    private void updateHeaderTotalsFromCache() {
        int sumTot = 0, sumNew = 0;
        synchronized (STATIC_FREQ_STATS_CACHE) {
            for (int[] s : STATIC_FREQ_STATS_CACHE.values()) {
                sumTot += s[0];
                sumNew += s[1];
            }
        }
        if (tvTotalAll != null) tvTotalAll.setText(String.valueOf(sumTot));
        if (tvTotalNew != null) tvTotalNew.setText(String.valueOf(sumNew));
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
        // [NEW] If scanning, pause instead of stop when fragment goes to background
        if (isScanning && !isScanPaused) {
            pauseScan();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        saveScanState();

        if (isScanning && !isScanPaused) {
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

        // [FIX] Clear ONLY temporary working data; static cache persists
        synchronized (this) {
            freqCallsigns.clear();
            currentScanFreq = -1;
            lastProcessedMessageIndex = 0;
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
            // [NEW] Pause scan instead of stopping when switching frequency
            pauseScan();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                switchToFrequency(RFfreq);
                if (navController != null) navController.navigate(R.id.menu_nav_mycalling);
            }, 100);
        } else {
            switchToFrequency(RFfreq);
            if (navController != null) navController.navigate(R.id.menu_nav_mycalling);
        }
    }

    // [NEW] Pause scan without clearing data - allows resume later
    private void pauseScan() {
        synchronized (freqSwitchLock) {
            if (!isScanning) return;
            isScanning = false;
            isScanPaused = true;

            // Save current state for resume
            pausedFreqIndex = currentFreqIndex;
            pausedCycles = scanCycles;
            synchronized (scanListLock) {
                if (activeScanFrequencies != null) {
                    pausedFreqList = new ArrayList<>(activeScanFrequencies);
                }
            }

            // Remove timer observer but keep message observer
            if (scanTimerObserver != null && mainViewModel != null) {
                try { mainViewModel.timerSec.removeObserver(scanTimerObserver); } catch (Exception ignored) {}
            }
            if (scanHandler != null) scanHandler.removeCallbacksAndMessages(null);

            // Update UI
            if (btnStartStop != null) btnStartStop.setText("Continue");
            updateScanSlotStatus(0, 0, -1);

            Log.d(TAG, "pauseScan: Paused at freq index " + pausedFreqIndex + ", cycles=" + pausedCycles);
        }
    }

    // [NEW] Resume paused scan from saved state
    private void continueScan() {
        synchronized (freqSwitchLock) {
            if (!isScanPaused || pausedFreqIndex < 0) {
                Log.w(TAG, "continueScan: Nothing to resume");
                return;
            }

            isScanning = true;
            isScanPaused = false;
            currentFreqIndex = pausedFreqIndex;
            scanCycles = pausedCycles;

            if (btnStartStop != null) btnStartStop.setText("Stop");

            Log.d(TAG, "continueScan: Resuming from freq index " + currentFreqIndex);

            // Resume scanning
            if (pausedFreqList != null && !pausedFreqList.isEmpty()) {
                scanNextFrequency(pausedFreqList);
            }
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

    // [NEW] Calculate and update UI stats for a single frequency IMMEDIATELY
    private void updateRowStatsForFrequency(long RFfreq) {
        Set<String> calls;
        synchronized (this) {
            calls = freqCallsigns.get(RFfreq);
        }
        if (calls == null || calls.isEmpty()) {
            Log.d(TAG, "updateRowStatsForFrequency: No callsigns for freq " + RFfreq);
            return;
        }

        int total = calls.size();
        int newCount = 0;
        Set<String> dxcc = new HashSet<>();
        Set<Integer> itu = new HashSet<>();
        Set<Integer> cq = new HashSet<>();

        Log.d(TAG, "updateRowStatsForFrequency: Processing " + calls.size() + " callsigns");

        for (String call : calls) {
            if (GeneralVariables.QSL_Callsign_list == null ||
                    !GeneralVariables.QSL_Callsign_list.contains(call)) {
                newCount++;
            }

            DatabaseOpr.StationRecord rec = DatabaseOpr.getStationRecord(call);

            String foundDxcc = null;
            int foundCq = 0;
            int foundItu = 0;

            if (rec != null) {
                // [EXACT FIELD NAMES from StationRecord class - NO FALLBACK]
                if (rec.dxccCode != null && !rec.dxccCode.isEmpty()) {
                    foundDxcc = rec.dxccCode;
                }
                if (rec.lastCqZone > 0) {
                    foundCq = rec.lastCqZone;
                }
                if (rec.lastItuZone > 0) {
                    foundItu = rec.lastItuZone;
                }

                // [DIAGNOSTIC] Log if record exists but fields are empty
                if (foundDxcc == null || foundCq == 0 || foundItu == 0) {
                    Log.w(TAG, "StationRecord for " + call + " has empty geo fields: " +
                            "dxccCode=" + rec.dxccCode +
                            " lastCqZone=" + rec.lastCqZone +
                            " lastItuZone=" + rec.lastItuZone);
                }
            } else {
                Log.w(TAG, "StationRecord is NULL for callsign: " + call);
            }

            if (foundDxcc != null && !foundDxcc.isEmpty()) dxcc.add(foundDxcc);
            if (foundCq > 0) cq.add(foundCq);
            if (foundItu > 0) itu.add(foundItu);
        }

        final int[] newStats = new int[]{total, newCount, dxcc.size(), cq.size(), itu.size()};

        // [NEW] Accumulate or overwrite based on continuous mode
        synchronized (STATIC_FREQ_STATS_CACHE) {
            if (selectedPostScanAction == ACTION_CONTINUOUS && STATIC_FREQ_STATS_CACHE.containsKey(RFfreq)) {
                int[] old = STATIC_FREQ_STATS_CACHE.get(RFfreq);
                old[0] += newStats[0];
                old[1] += newStats[1];
                old[2] += newStats[2];
                old[3] += newStats[3];
                old[4] += newStats[4];
            } else {
                STATIC_FREQ_STATS_CACHE.put(RFfreq, newStats.clone());
            }
        }

        if (getActivity() == null) return;
        requireActivity().runOnUiThread(() -> {
            updateRowWithStats(RFfreq, STATIC_FREQ_STATS_CACHE.get(RFfreq));
            updateHeaderTotalsFromCache();
            Log.d(TAG, "updateRowStatsForFrequency: UI Updated for " + RFfreq +
                    " | Total=" + newStats[0] + " | New=" + newStats[1] +
                    " | D=" + newStats[2] + " | C=" + newStats[3] + " | I=" + newStats[4]);
        });
    }

    // [NEW] Collect and process messages for a specific frequency
    private void collectAndProcessMessagesForFrequency(long RFfreq) {
        if (mainViewModel == null) return;

        ArrayList<Ft8Message> messages = mainViewModel.mutableFt8MessageList.getValue();
        if (messages == null) return;

        int startIdx = lastProcessedMessageIndex;
        int endIdx = messages.size();

        if (startIdx >= endIdx) {
            Log.d(TAG, "collectAndProcessMessagesForFrequency: No new messages for freq " + RFfreq);
            return;
        }

        Log.d(TAG, "collectAndProcessMessagesForFrequency: Processing messages " + startIdx + " to " + endIdx +
                " for freq " + RFfreq);

        try {
            for (int i = startIdx; i < endIdx; i++) {
                Ft8Message msg = messages.get(i);
                String callsign = msg.getCallsignFrom();
                if (callsign != null && !callsign.isEmpty()) {
                    String upperCall = callsign.toUpperCase();
                    synchronized (this) {
                        freqCallsigns.computeIfAbsent(RFfreq, k -> new HashSet<>())
                                .add(upperCall);
                    }
                }
            }
            lastProcessedMessageIndex = endIdx;

            Log.d(TAG, "collectAndProcessMessagesForFrequency: Collected " +
                    freqCallsigns.get(RFfreq).size() + " unique callsigns for " + RFfreq);
        } catch (Exception e) {
            Log.e(TAG, "Error processing messages for freq " + RFfreq + ": " + e.getMessage());
        }
    }

    // [NEW] Find frequency with maximum value in specified stat column
    // stats array: [0]=total, [1]=new, [2]=dxcc, [3]=cq, [4]=itu
    private long findFreqWithMaxStat(int statIndex) {
        long bestFreq = -1;
        int maxVal = -1;
        synchronized (STATIC_FREQ_STATS_CACHE) {
            for (Map.Entry<Long, int[]> entry : STATIC_FREQ_STATS_CACHE.entrySet()) {
                if (entry.getValue() != null && entry.getValue().length > statIndex) {
                    if (entry.getValue()[statIndex] > maxVal) {
                        maxVal = entry.getValue()[statIndex];
                        bestFreq = entry.getKey();
                    }
                }
            }
        }
        return bestFreq;
    }

    // [NEW] Switch to frequency and trigger CQ calling sequence
    // TODO: Replace the commented call with the exact method from your calling window/viewmodel
    private void switchToAndStartCalling(long freq) {
        if (freq <= 0) return;
        Log.d(TAG, "switchToAndStartCalling: Switching to " + freq + " and starting call");
        switchToFrequency(freq);

        // [TODO] Hook into your calling window logic here. Example:
        // if (mainViewModel != null) mainViewModel.startCQCall();
        // or navigate to calling fragment and trigger transmission
        Toast.makeText(getContext(), "Switched to " + freq + ". Starting CQ call...", Toast.LENGTH_SHORT).show();
    }

    private void startRealScan() {
        if (containerScanContent == null) return;
        if (mainViewModel == null || mainViewModel.ft8SignalListener == null) {
            Log.e(TAG, "Cannot start scan: listener not ready"); return;
        }

        currentFreqIndex = 0;
        currentCycle = 1;
        synchronized (this) {
            freqCallsigns.clear();
            currentScanFreq = -1;
            lastProcessedMessageIndex = 0;
        }

        // [FIX] Clear static cache ONLY when starting a truly fresh scan (not continuous loop)
        if (selectedPostScanAction != ACTION_CONTINUOUS) {
            synchronized (STATIC_FREQ_STATS_CACHE) {
                STATIC_FREQ_STATS_CACHE.clear();
            }
            STATIC_SCAN_COMPLETED = false;
        }

        resetRowCounters();
        updateHeaderTotalsFromCache();

        if (mainViewModel.databaseOpr != null) {
            loadWorkedCallsigns();
        }

        List<Long> initialFreqs;
        synchronized (scanListLock) { initialFreqs = new ArrayList<>(activeScanFrequencies); }
        if (initialFreqs.isEmpty()) {
            Toast.makeText(getContext(), "No frequencies selected", Toast.LENGTH_SHORT).show();
            isScanning = false; if (btnStartStop != null) btnStartStop.setText("Start"); return;
        }

        Log.d(TAG, "startRealScan: === STARTING SCAN (Cycle " + currentCycle + ") ===");

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
                if (!isScanPaused) {
                    stopScan();
                    Toast.makeText(getContext(), "Scan complete", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            // [NEW] Handle cycle completion based on spinner selection
            if (currentFreqIndex >= currentList.size()) {
                switch (selectedPostScanAction) {
                    case ACTION_CONTINUOUS:
                        currentCycle++;
                        Log.d(TAG, "Continuous: Cycle " + currentCycle + " complete. Restarting...");
                        currentFreqIndex = 0;
                        freqCallsigns.clear(); // Fresh set for new cycle
                        Toast.makeText(getContext(), "Cycle " + (currentCycle - 1) + " done. Restarting...", Toast.LENGTH_SHORT).show();
                        scanNextFrequency(currentList);
                        return;

                    case ACTION_SWITCH_MAX_NEW:
                        switchToAndStartCalling(findFreqWithMaxStat(1)); // index 1 = new
                        stopScan();
                        return;

                    case ACTION_SWITCH_MAX_DX:
                        switchToAndStartCalling(findFreqWithMaxStat(2)); // index 2 = dxcc
                        stopScan();
                        return;

                    case ACTION_SWITCH_MAX_ITU:
                        switchToAndStartCalling(findFreqWithMaxStat(4)); // index 4 = itu
                        stopScan();
                        return;

                    case ACTION_SWITCH_MAX_CQ: // Used for Country/DXCC mapping
                        switchToAndStartCalling(findFreqWithMaxStat(3)); // index 3 = cq
                        stopScan();
                        return;

                    case ACTION_STOP:
                    default:
                        Log.d(TAG, "scanNextFrequency: All frequencies scanned. Stopping.");
                        postScanCalculateAndShow();
                        return;
                }
            }

            long RFfreq = currentList.get(currentFreqIndex);
            Log.d(TAG, "scanNextFrequency: Selected RF freq=" + RFfreq);

            if (mainViewModel.baseRig == null || !mainViewModel.baseRig.isConnected()) {
                Log.e(TAG, "Rig disconnected during scan");
                Toast.makeText(getContext(), "Rig disconnected", Toast.LENGTH_SHORT).show();
                pauseScan(); return;
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
                pauseScan(); return;
            }

            synchronized (this) {
                currentScanFreq = RFfreq;
                freqCallsigns.putIfAbsent(RFfreq, new HashSet<>());
            }

            Log.d(TAG, "scanNextFrequency: Starting listen cycle immediately for " + RFfreq);
            if (isScanning) {
                listenForMessages(RFfreq, scanCycles, currentList);
            }
        }
    }

    // OLD CODE - BEGIN (DO NOT DELETE, ONLY COMMENT)
    /*
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
                currentFreqIndex++;
                scanNextFrequency(currentList);
            }
        };
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), scanTimerObserver);
    }
    */
    // OLD CODE - END

    // NEW CODE - BEGIN
    private void listenForMessages(long RFfreq, int cycles, List<Long> currentList) {
        if (scanHandler == null || !isScanning) return;
        final int[] slotsProcessed = {0};
        final int startSequential = UtcTimer.getNowSequential();

        updateScanSlotStatus(0, cycles, RFfreq);

        scanTimerObserver = utcMillis -> {
            if (!isScanning) {
                mainViewModel.timerSec.removeObserver(scanTimerObserver);
                return;
            }
            int currentSequential = UtcTimer.sequential(utcMillis);
            int expectedSequential = (startSequential + slotsProcessed[0]) % 2;
            if (currentSequential != expectedSequential) return;

            slotsProcessed[0]++;

            Log.d(TAG, "listenForMessages: Slot " + slotsProcessed[0] + "/" + cycles + " completed on " + RFfreq);

            updateScanSlotStatus(slotsProcessed[0], cycles, RFfreq);

            if (slotsProcessed[0] >= cycles) {
                mainViewModel.timerSec.removeObserver(scanTimerObserver);

                collectAndProcessMessagesForFrequency(RFfreq);
                updateRowStatsForFrequency(RFfreq);

                currentFreqIndex++;
                updateScanSlotStatus(0, 0, -1);
                scanNextFrequency(currentList);
            }
        };
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), scanTimerObserver);
    }
    // NEW CODE - END

    private void postScanCalculateAndShow() {
        // [FIX] In continuous/switch modes, this is skipped. Headers are updated incrementally.
        if (selectedPostScanAction != ACTION_STOP) return;

        STATIC_SCAN_COMPLETED = true;
        Toast.makeText(getContext(), "Scan complete", Toast.LENGTH_SHORT).show();
        stopScan();
    }

    private void updateRowWithStats(long RFfreq, int[] stats) {
        if (containerScanContent == null) return;

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
                break;
            }
        }
    }

    private void stopScan() {
        synchronized (freqSwitchLock) {
            if (!isScanning && !isScanPaused) return;
            isScanning = false;
            isScanPaused = false;

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

    private void updateScanSlotStatus(int current, int total, long freqHz) {
        if (getActivity() == null || tvScanSlotStatus == null) return;

        requireActivity().runOnUiThread(() -> {
            if (tvScanSlotStatus == null) return;

            if (total > 0) {
                String prefix = selectedPostScanAction == ACTION_CONTINUOUS ? "Cyc." + currentCycle + ": " : "";
                String status = prefix + "Slot " + current + "/" + total;
                tvScanSlotStatus.setText(status);
                tvScanSlotStatus.setTextColor(getResources().getColor(R.color.is_qsl_text_color, null));
            } else {
                tvScanSlotStatus.setText("--/--");
                tvScanSlotStatus.setTextColor(getResources().getColor(R.color.is_qsl_text_color, null));
            }
        });
    }

}