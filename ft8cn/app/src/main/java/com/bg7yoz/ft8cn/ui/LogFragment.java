package com.bg7yoz.ft8cn.ui;

/**
 * Main interface for QSO logs.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.log.LogFileImport;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.log.ShareLogs;
import com.bg7yoz.ft8cn.databinding.FragmentLogBinding;
import com.bg7yoz.ft8cn.grid_tracker.GridTrackerMainActivity;
import com.bg7yoz.ft8cn.html.LogHttpServer;
import com.bg7yoz.ft8cn.log.LogCallsignAdapter;
import com.bg7yoz.ft8cn.log.LogQSLAdapter;
import com.bg7yoz.ft8cn.log.OnQueryQSLCallsign;
import com.bg7yoz.ft8cn.log.OnQueryQSLRecordCallsign;
import com.bg7yoz.ft8cn.log.QSLCallsignRecord;
import com.bg7yoz.ft8cn.log.QSLRecordStr;
import com.bg7yoz.ft8cn.log.OnShareLogEvents;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;


public class LogFragment extends Fragment {
    private static final String TAG = "LogFragment";
    private static final int REQUEST_CODE_IMPORT_LOG = 1001;
    private static final int REQUEST_CODE_EXPORT_LOG = 1002;
    private FragmentLogBinding binding;
    private MainViewModel mainViewModel;

    private LogCallsignAdapter logCallsignAdapter;
    private LogQSLAdapter logQSLAdapter;
    private boolean loading = false;
    private int lastItemPosition;
    private ShareLogsProgressDialog dialog = null;

    // [NEW] Export filter parameters holder - matches WebServer export params
    private static class ExportFilter {
        public final String startDate;    // YYYY-MM-DD or null
        public final String endDate;      // YYYY-MM-DD or null
        public final String band;         // e.g. "20m", "40m", or null for ALL
        public final String callsignPrefix; // prefix or null

        public ExportFilter(String startDate, String endDate, String band, String callsignPrefix) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.band = band;
            this.callsignPrefix = callsignPrefix;
        }

        // [NEW] Build SQL WHERE clause from filter params
        public String buildWhereClause() {
            StringBuilder where = new StringBuilder();
            boolean first = true;
            if (startDate != null && !startDate.isEmpty()) {
                where.append(first ? "" : " AND ").append("qso_date >= '").append(startDate).append("'");
                first = false;
            }
            if (endDate != null && !endDate.isEmpty()) {
                where.append(first ? "" : " AND ").append("qso_date <= '").append(endDate).append("'");
                first = false;
            }
            if (band != null && !band.isEmpty() && !"ALL".equalsIgnoreCase(band)) {
                // Convert band name to frequency range for DB filter
                String freqCondition = bandToFrequencyRange(band);
                if (freqCondition != null) {
                    where.append(first ? "" : " AND ").append(freqCondition);
                    first = false;
                }
            }
            if (callsignPrefix != null && !callsignPrefix.isEmpty()) {
                where.append(first ? "" : " AND ").append("call LIKE '").append(callsignPrefix).append("%'");
            }
            return where.length() > 0 ? "WHERE " + where.toString() : "";
        }

        // [NEW] Convert band name to frequency range condition for QSLTable.freq_khz
        private String bandToFrequencyRange(String band) {
            // Frequency ranges in kHz for common FT8 bands
            switch (band.toUpperCase(Locale.US)) {
                case "160M": return "freq_khz BETWEEN 1800 AND 2000";
                case "80M": return "freq_khz BETWEEN 3500 AND 4000";
                case "60M": return "freq_khz BETWEEN 5250 AND 5450";
                case "40M": return "freq_khz BETWEEN 7000 AND 7300";
                case "30M": return "freq_khz BETWEEN 10100 AND 10150";
                case "20M": return "freq_khz BETWEEN 14000 AND 14350";
                case "17M": return "freq_khz BETWEEN 18068 AND 18168";
                case "15M": return "freq_khz BETWEEN 21000 AND 21450";
                case "12M": return "freq_khz BETWEEN 24890 AND 24990";
                case "10M": return "freq_khz BETWEEN 28000 AND 29700";
                case "6M": return "freq_khz BETWEEN 50000 AND 54000";
                default: return null; // Unknown band - no filter
            }
        }
    }

    // [NEW] Interface for filter dialog callback
    private interface OnExportFilterConfirmed {
        void onConfirmed(ExportFilter filter);
    }


    public LogFragment() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainViewModel = MainViewModel.getInstance(this);
    }

    @SuppressLint({"DefaultLocale", "NotifyDataSetChanged"})
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentLogBinding.inflate(getLayoutInflater());

        logCallsignAdapter = new LogCallsignAdapter(requireContext(), mainViewModel);
        logQSLAdapter = new LogQSLAdapter(requireContext(), mainViewModel);
        binding.logRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        setShowStyle();
        initRecyclerViewAction();

        binding.countImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCountFragment();
            }
        });

        binding.inputMycallEdit.setText(mainViewModel.queryKey);
        queryByCallsign(mainViewModel.queryKey, 0);

        mainViewModel.mutableQueryFilter.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                queryByCallsign(mainViewModel.queryKey, 0);
            }
        });

        binding.inputMycallEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                mainViewModel.queryKey = editable.toString();
                queryByCallsign(mainViewModel.queryKey, 0);
            }
        });

        binding.filterImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new FilterDialog(requireContext(), mainViewModel).show();
            }
        });

        binding.exportImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getLocalIp() == null) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.export_null)
                            , false).show();
                } else {
                    new HelpDialog(requireContext(), requireActivity()
                            , String.format(GeneralVariables.getStringFromResource(R.string.export_info)
                            , getLocalIp(), LogHttpServer.DEFAULT_PORT)
                            , false).show();
                }
            }
        });

        binding.shareLogImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                buildShareLogs();
            }
        });

        binding.btnImportLogDownloads.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                importLogFromDownloads();
            }
        });

        binding.btnExportLogDownloads.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // [MODIFIED] Show filter dialog before export
                // [OBSOLETE] Direct export without filters:
                // exportLogToDownloads();

                showExportFilterDialog(new OnExportFilterConfirmed() {
                    @Override
                    public void onConfirmed(ExportFilter filter) {
                        // Store filter for use in exportAdifToFile
                        currentExportFilter = filter;
                        exportLogToDownloads();
                    }
                });
            }
        });

        binding.logViewStyleimageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.logListShowCallsign = !mainViewModel.logListShowCallsign;
                setShowStyle();
                queryByCallsign(binding.inputMycallEdit.getText().toString(), 0);
            }
        });

        binding.locationInMapImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(requireContext(), GridTrackerMainActivity.class);
                intent.putExtra("qslAll", mainViewModel.queryKey);
                intent.putExtra("queryFilter", mainViewModel.queryFilter);
                startActivity(intent);
            }
        });

        if (Boolean.TRUE.equals(mainViewModel.mutableShareRunning.getValue())) {
            showShareDialog();
        }

        return binding.getRoot();
    }

    // [NEW] Current export filter - used between dialog and export method
    private ExportFilter currentExportFilter = null;

    // [NEW] Show export filter dialog - inline implementation, no separate class file
    // [MODIFIED] Added quick date buttons (Today/Yesterday/DayBefore) for From field
    private void showExportFilterDialog(OnExportFilterConfirmed callback) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View view = inflater.inflate(R.layout.dialog_export_filter, null);

        EditText etStartDate = view.findViewById(R.id.etStartDate);
        EditText etEndDate = view.findViewById(R.id.etEndDate);
        Spinner spBand = view.findViewById(R.id.spBand);
        EditText etCallsign = view.findViewById(R.id.etCallsignPrefix);

        // [NEW] Quick date buttons
        android.widget.Button btnToday = view.findViewById(R.id.btnToday);
        android.widget.Button btnYesterday = view.findViewById(R.id.btnYesterday);
        android.widget.Button btnDayBefore = view.findViewById(R.id.btnDayBefore);

        // [NEW] Button references for custom button bar
        android.widget.Button btnExportAll = view.findViewById(R.id.btnExportAll);
        android.widget.Button btnCancel = view.findViewById(R.id.btnCancel);
        android.widget.Button btnExport = view.findViewById(R.id.btnExport);

        // Band list - matches common FT8 bands
        // [OBSOLETE] Could use Bands.getBandsList() if available in future
        String[] bands = new String[] {"ALL", "160m", "80m", "40m", "20m", "17m", "15m", "12m", "10m", "6m"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, bands);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBand.setAdapter(adapter);

        // [NEW] Helper: set date in YYYY-MM-DD format
        // Uses java.text.SimpleDateFormat for consistent formatting
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        // [NEW] Quick date button handlers
        btnToday.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etStartDate.setText(sdf.format(new java.util.Date()));
            }
        });

        btnYesterday.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
                etStartDate.setText(sdf.format(cal.getTime()));
            }
        });

        btnDayBefore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                cal.add(java.util.Calendar.DAY_OF_YEAR, -2);
                etStartDate.setText(sdf.format(cal.getTime()));
            }
        });

        // [NEW] AlertDialog without default buttons - we use custom button bar
        AlertDialog alertDialog = new AlertDialog.Builder(requireContext())
                .setTitle("Export Filter")
                .setView(view)
                .create();

        // [NEW] EXPORT ALL button handler - bypass filters, export everything
        btnExportAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                alertDialog.dismiss();
                // Export with null filter = original behavior, no filters
                if (callback != null) {
                    callback.onConfirmed(null);
                }
            }
        });

        // [NEW] Cancel button handler
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                alertDialog.dismiss();
            }
        });

        // [NEW] Export button handler - apply filters from form
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String start = etStartDate.getText().toString().trim();
                String end = etEndDate.getText().toString().trim();
                String band = spBand.getSelectedItem().toString();
                String prefix = etCallsign.getText().toString().trim().toUpperCase(java.util.Locale.US);

                // Build filter - empty values mean no filter for that field
                // [NOTE] If end is empty, buildWhereClause() will not add upper date bound
                //        which means "export up to the latest record" - as requested
                ExportFilter filter = new ExportFilter(
                        start.isEmpty() ? null : start,
                        end.isEmpty() ? null : end,
                        "ALL".equals(band) ? null : band,
                        prefix.isEmpty() ? null : prefix
                );

                alertDialog.dismiss();
                if (callback != null) {
                    callback.onConfirmed(filter);
                }
            }
        });

        alertDialog.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_IMPORT_LOG && resultCode == requireActivity().RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Log.d(TAG, "File selected for import: " + uri.toString());
                importAdifFile(uri);
            } else {
                Toast.makeText(requireContext(), "No file URI received for import", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CODE_EXPORT_LOG && resultCode == requireActivity().RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Log.d(TAG, "File selected for export: " + uri.toString());
                // [MODIFIED] Pass filter to export method (null = export all)
                exportAdifToFile(uri, currentExportFilter);
                // Clear filter after use
                currentExportFilter = null;
            } else {
                Toast.makeText(requireContext(), "No file URI received for export", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CODE_EXPORT_LOG && resultCode == requireActivity().RESULT_CANCELED) {
            Toast.makeText(requireContext(), "Export cancelled", Toast.LENGTH_SHORT).show();
            currentExportFilter = null;
        }
    }

    private void importLogFromDownloads() {
        Log.d(TAG, "Opening file picker for import");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream",
                "text/plain",
                "text/csv",
                "application/adif"
        });
        startActivityForResult(intent, REQUEST_CODE_IMPORT_LOG);
    }

    private void importAdifFile(Uri uri) {
        Toast.makeText(requireContext(), "Reading file...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File tempFile = null;
            try {
                // 1. Read content from URI
                InputStream is = requireContext().getContentResolver().openInputStream(uri);
                if (is == null) {
                    throw new IOException("Failed to open file stream");
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                is.close();

                String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                Log.d(TAG, "File read. Size: " + content.length() + " chars");

                // 2. Write to temporary file (LogFileImport expects a file path)
                tempFile = File.createTempFile("ft8cn_import_", ".adi", requireContext().getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                }

                // 3. Parse using LogFileImport with file path
                LogFileImport logFileImport = new LogFileImport(null, tempFile.getAbsolutePath());
                ArrayList<HashMap<String, String>> records = logFileImport.getLogRecords();
                int parseErrors = logFileImport.getErrorCount();

                Log.d(TAG, "Parsed records: " + (records != null ? records.size() : 0) + ", Parse errors: " + parseErrors);

                if (records == null || records.isEmpty()) {
                    String errorMsg = parseErrors > 0 ? "Parse errors: " + parseErrors : "No valid ADIF records found";
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show());
                    return;
                }

                Log.d(TAG, "Starting DB insert for " + records.size() + " records...");
                int imported = 0;
                int skipped = 0;
                int duplicate = 0;
                for (HashMap<String, String> record : records) {
                    try {
                        QSLRecord qslRecord = new QSLRecord(record);
                        boolean result = mainViewModel.databaseOpr.doInsertQSLData(qslRecord, null);
                        if (result) {
                            imported++;
                        } else {
                            // Check if it is a duplicate by querying the DB
                            Cursor check = mainViewModel.databaseOpr.getDb().rawQuery(
                                    "SELECT COUNT(*) FROM QSLTable WHERE call=? AND qso_date=? AND time_on=?",
                                    new String[]{record.get("call"), record.get("qso_date"), record.get("time_on")});
                            if (check.moveToFirst() && check.getInt(0) > 1) {
                                duplicate++;
                            } else {
                                skipped++;
                            }
                            check.close();
                        }
                    } catch (Exception dbEx) {
                        Log.e(TAG, "DB insert error: " + dbEx.getMessage());
                        skipped++;
                    }
                }

                final int finalImported = imported;
                final int finalSkipped = skipped;
                final int finalDuplicate = duplicate;
                final int finalParseErrors = parseErrors;
                requireActivity().runOnUiThread(() -> {
                    StringBuilder msg = new StringBuilder("Imported: " + finalImported);
                    if (finalDuplicate > 0) msg.append(", Duplicates: ").append(finalDuplicate);
                    if (finalSkipped > 0) msg.append(", Skipped: ").append(finalSkipped);
                    if (finalParseErrors > 0) msg.append(", Parse errors: ").append(finalParseErrors);
                    Toast.makeText(requireContext(), msg.toString(), Toast.LENGTH_LONG).show();
                    queryByCallsign(mainViewModel.queryKey, 0);
                });

            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                // 4. Clean up temp file
                if (tempFile != null && tempFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
            }
        }).start();
    }

    private void exportLogToDownloads() {
        // Use ACTION_CREATE_DOCUMENT to let user pick filename and location
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "FT8CN_export_" + getUtcDateString() + ".adi");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "text/plain"});
        startActivityForResult(intent, REQUEST_CODE_EXPORT_LOG);
    }

    // [MODIFIED] Added filter parameter support
    private void exportAdifToFile(Uri uri, ExportFilter filter) {
        Toast.makeText(requireContext(), "Exporting...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // [MODIFIED] Apply filter to SQL query if provided
                String whereClause = (filter != null) ? filter.buildWhereClause() : "";
                String orderBy = " ORDER BY qso_date DESC";
                String query = "SELECT * FROM QSLTable " + whereClause + orderBy;

                Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery(query, null);
                String adifContent = mainViewModel.databaseOpr.downQSLTable(cursor, false);
                cursor.close();

                try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(adifContent.getBytes(StandardCharsets.UTF_8));
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "Export successful", Toast.LENGTH_SHORT).show());
                    } else {
                        throw new IOException("Failed to open output stream");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // [OBSOLETE] Original method without filter - kept for reference, commented
    /*
    private void exportAdifToFile(Uri uri) {
        Toast.makeText(requireContext(), "Exporting...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Cursor cursor = mainViewModel.databaseOpr.getDb()
                        .rawQuery("SELECT * FROM QSLTable ORDER BY qso_date DESC", null);
                String adifContent = mainViewModel.databaseOpr.downQSLTable(cursor, false);
                cursor.close();

                try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(adifContent.getBytes(StandardCharsets.UTF_8));
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "Export successful", Toast.LENGTH_SHORT).show());
                    } else {
                        throw new IOException("Failed to open output stream");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
    */

    private String getUtcDateString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private void showShareDialog() {
        mainViewModel.mutableShareRunning.setValue(true);
        dialog = new ShareLogsProgressDialog(
                binding.getRoot().getContext()
                , mainViewModel, false);
        dialog.show();
        mainViewModel.mutableSharePosition.postValue(0);
        mainViewModel.mutableShareInfo.postValue("");
        mainViewModel.mutableShareCount.postValue(0);
    }

    private void buildShareLogs() {
        showShareDialog();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File adiFile = GeneralVariables.writeToTempFile(requireContext()
                        , "FT8CN"
                        , ".txt"
                        , "");
                new ShareLogs().doShareLogs(requireContext(), adiFile
                        , GeneralVariables.getStringFromResource(R.string.share_logs)
                        , mainViewModel.databaseOpr.getDb()
                        , mainViewModel.queryKey
                        , mainViewModel.queryFilter
                        , adiFile
                        , false
                        , new OnShareLogEvents() {
                            @Override
                            public void onPreparing(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }
                            @Override
                            public void onShareStart(int count, String info) {
                                mainViewModel.mutableSharePosition.postValue(0);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareRunning.postValue(true);
                                mainViewModel.mutableShareCount.postValue(count);
                            }
                            @Override
                            public boolean onShareProgress(int count, int position, String info) {
                                mainViewModel.mutableSharePosition.postValue(position);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareCount.postValue(count);
                                return Boolean.TRUE.equals(mainViewModel.mutableShareRunning.getValue());
                            }
                            @Override
                            public void afterGet(int count, String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareRunning.postValue(false);
                            }
                            @Override
                            public void onShareFailed(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }
                        });
            }
        }).start();
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        int position = (Integer) item.getActionView().getTag();
        if (!mainViewModel.logListShowCallsign) {
            switch (item.getItemId()) {
                case 0:
                    logQSLAdapter.setRecordIsQSL(position, false);
                    logQSLAdapter.notifyItemChanged(position);
                    break;
                case 1:
                    logQSLAdapter.setRecordIsQSL(position, true);
                    logQSLAdapter.notifyItemChanged(position);
                    break;
                case 2:
                    showQrzFragment(logQSLAdapter.getRecord(position).getCall());
                    break;
                case 3:
                    Intent intent = new Intent(requireContext(), GridTrackerMainActivity.class);
                    intent.putExtra("qslList", logQSLAdapter.getRecord(position));
                    startActivity(intent);
                    break;
            }
        } else {
            if (item.getItemId() == 2) {
                showQrzFragment(logCallsignAdapter.getRecord(position).getCallsign());
            }
        }
        return super.onContextItemSelected(item);
    }

    private void loadQueryData() {
        if ((!loading)) {
            if (mainViewModel.logListShowCallsign) {
                queryByCallsign(mainViewModel.queryKey, logCallsignAdapter.getItemCount());
            } else {
                queryByCallsign(mainViewModel.queryKey, logQSLAdapter.getItemCount());
            }
        }
    }

    private void initRecyclerViewAction() {
        binding.logRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                int itemCount;
                if (mainViewModel.logListShowCallsign) {
                    itemCount = logCallsignAdapter.getItemCount();
                } else {
                    itemCount = logQSLAdapter.getItemCount();
                }
                if (newState == SCROLL_STATE_IDLE && lastItemPosition == itemCount) {
                    loadQueryData();
                }
            }
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
                if (layoutManager instanceof LinearLayoutManager) {
                    LinearLayoutManager manager = (LinearLayoutManager) layoutManager;
                    int firstVisibleItem = manager.findFirstVisibleItemPosition();
                    int l = manager.findLastCompletelyVisibleItemPosition();
                    lastItemPosition = firstVisibleItem + (l - firstVisibleItem) + 1;
                }
            }
        });

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.ANIMATION_TYPE_DRAG
                , ItemTouchHelper.END | ItemTouchHelper.START) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder
                    , @NonNull RecyclerView.ViewHolder target) {
                return false;
            }
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                if (direction == ItemTouchHelper.END) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
                    builder.setIcon(null);
                    builder.setTitle(GeneralVariables.getStringFromResource(R.string.delete_confirmation));
                    builder.setMessage(GeneralVariables.getStringFromResource(R.string.are_you_sure_delete));
                    builder.setPositiveButton(GeneralVariables.getStringFromResource(R.string.ok_confirmed)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    logQSLAdapter.deleteRecord(viewHolder.getAdapterPosition());
                                    logQSLAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                                }
                            });
                    builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialogInterface) {
                            logQSLAdapter.notifyDataSetChanged();
                        }
                    });
                    builder.setNegativeButton(GeneralVariables.getStringFromResource(R.string.cancel)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    logQSLAdapter.notifyDataSetChanged();
                                }
                            }).show();
                }
                if (direction == ItemTouchHelper.START) {
                    logQSLAdapter.setRecordIsQSL(viewHolder.getAdapterPosition()
                            , !logQSLAdapter.getRecord(viewHolder.getAdapterPosition()).isQSL);
                    logQSLAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
                }
            }
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder) {
                int swipeFlag;
                if (mainViewModel.logListShowCallsign) {
                    swipeFlag = 0;
                } else {
                    swipeFlag = ItemTouchHelper.START | ItemTouchHelper.END;
                }
                return makeMovementFlags(0, swipeFlag);
            }
            final Drawable delIcon = ContextCompat.getDrawable(requireActivity()
                    , R.drawable.log_item_delete_icon);
            final Drawable qslIcon = ContextCompat.getDrawable(requireActivity()
                    , R.drawable.ic_baseline_library_add_check_24);
            final Drawable background = new ColorDrawable(Color.LTGRAY);
            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY
                    , int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                Drawable icon;
                View itemView = viewHolder.itemView;
                if (dX > 0) {
                    icon = delIcon;
                } else {
                    icon = qslIcon;
                }
                int iconMargin = (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                int iconLeft, iconRight, iconTop, iconBottom;
                int backTop, backBottom, backLeft, backRight;
                backTop = itemView.getTop();
                backBottom = itemView.getBottom();
                iconTop = itemView.getTop() + (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                iconBottom = iconTop + icon.getIntrinsicHeight();
                if (dX > 0) {
                    backLeft = itemView.getLeft();
                    backRight = itemView.getLeft() + (int) dX;
                    background.setBounds(backLeft, backTop, backRight, backBottom);
                    iconLeft = itemView.getLeft() + iconMargin;
                    iconRight = iconLeft + icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                } else if (dX < 0) {
                    backRight = itemView.getRight();
                    backLeft = itemView.getRight() + (int) dX;
                    background.setBounds(backLeft, backTop, backRight, backBottom);
                    iconRight = itemView.getRight() - iconMargin;
                    iconLeft = iconRight - icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                } else {
                    background.setBounds(0, 0, 0, 0);
                    icon.setBounds(0, 0, 0, 0);
                }
                background.draw(c);
                icon.draw(c);
            }
        }).attachToRecyclerView(binding.logRecyclerView);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setShowStyle() {
        if (mainViewModel.logListShowCallsign) {
            binding.logViewStyleimageButton.setImageResource(R.drawable.ic_baseline_assignment_ind_24);
            binding.logRecyclerView.setAdapter(logCallsignAdapter);
            logCallsignAdapter.notifyDataSetChanged();
            binding.locationInMapImageButton.setVisibility(View.GONE);
        } else {
            binding.logViewStyleimageButton.setImageResource(R.drawable.ic_baseline_assignment_24);
            binding.logRecyclerView.setAdapter(logQSLAdapter);
            logQSLAdapter.notifyDataSetChanged();
            binding.locationInMapImageButton.setVisibility(View.VISIBLE);
        }
    }

    private void queryByCallsign(String callsign, int offset) {
        loading = true;
        if (mainViewModel.logListShowCallsign) {
            if (offset == 0) {
                logCallsignAdapter.clearRecords();
            }
            mainViewModel.databaseOpr.getQSLCallsignsByCallsign(false, offset, callsign, mainViewModel.queryFilter
                    , new OnQueryQSLCallsign() {
                        @Override
                        public void afterQuery(ArrayList<QSLCallsignRecord> records) {
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    logCallsignAdapter.setQSLCallsignList(records);
                                    loading = false;
                                }
                            });
                        }
                    });
        } else {
            if (offset == 0) {
                logQSLAdapter.clearRecords();
            }
            mainViewModel.databaseOpr.getQSLRecordByCallsign(false, offset, callsign, mainViewModel.queryFilter
                    , new OnQueryQSLRecordCallsign() {
                        @Override
                        public void afterQuery(ArrayList<QSLRecordStr> records) {
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    logQSLAdapter.setQSLList(records);
                                    loading = false;
                                }
                            });
                        }
                    });
        }
    }

    private void showCountFragment() {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity()
                .getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;
        navHostFragment.getNavController().navigate(R.id.countFragment);
    }

    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity()
                .getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }

    @Nullable
    private String getLocalIp() {
        WifiManager wifiManager = (WifiManager) requireContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipAddress = wifiInfo.getIpAddress();
        if (ipAddress == 0) {
            return null;
        }
        return ((ipAddress & 0xff) + "." + (ipAddress >> 8 & 0xff) + "." + (ipAddress >> 16 & 0xff)
                + "." + (ipAddress >> 24 & 0xff));
    }

    @Override
    public void onDestroy() {
        if (Boolean.TRUE.equals(mainViewModel.mutableShareRunning.getValue())) {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }
        super.onDestroy();
    }
}