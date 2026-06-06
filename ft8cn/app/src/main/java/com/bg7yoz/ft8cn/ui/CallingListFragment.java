//CallingListFragment.java
package com.bg7yoz.ft8cn.ui;

/**
 * Decode interface fragment.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.databinding.FragmentCallingListBinding;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.decisions.StationState;

import java.util.ArrayList;

public class CallingListFragment extends Fragment {
    private static final String TAG = "CallingListFragment";

    // [NEW] Menu item IDs for FT8 state queue options (only for popup menu)
    private static final int MENU_FOLLOW_TO = 0;
    private static final int MENU_CALL_TO = 1;
    private static final int MENU_FOLLOW_FROM = 2;
    private static final int MENU_CALL_FROM = 3;
    private static final int MENU_REPLY = 4;
    private static final int MENU_QRZ_TO = 5;
    private static final int MENU_QRZ_FROM = 6;
    private static final int MENU_LOG_TO = 7;
    private static final int MENU_LOG_FROM = 8;
    // [NEW] FT8 state menu items - route through existing transmit logic
    private static final int MENU_STATE_1 = 9;
    private static final int MENU_STATE_2 = 10;
    private static final int MENU_STATE_3 = 11;
    private static final int MENU_STATE_4 = 12;

    private FragmentCallingListBinding binding;

    // [NEW] TextView for RF frequency display
    private TextView rfFrequencyTextView;

    private RecyclerView callListRecyclerView;
    private CallingListAdapter callingListAdapter;
    private MainViewModel mainViewModel;


    @SuppressLint("NotifyDataSetChanged")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentCallingListBinding.inflate(inflater, container, false);

        // [NEW] Initialize RF frequency TextView
        rfFrequencyTextView = binding.getRoot().findViewById(R.id.rfFrequencyTextView);
        updateRfFrequencyDisplay();

        callListRecyclerView = binding.callingListRecyclerView;

        callingListAdapter = new CallingListAdapter(this.getContext(), mainViewModel
                , mainViewModel.ft8Messages, CallingListAdapter.ShowMode.CALLING_LIST);
        callListRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        callListRecyclerView.setAdapter(callingListAdapter);
        callingListAdapter.notifyDataSetChanged();
        callListRecyclerView.scrollToPosition(callingListAdapter.getItemCount() - 1);


        requireActivity().registerForContextMenu(callListRecyclerView); // Register for context menu

        // Show spectrum view in landscape mode
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            assert binding.spectrumView != null;
            binding.spectrumView.run(mainViewModel, this);
        }
        // Setup call sign swipe actions for quick calling
        initRecyclerViewAction();

        // Listen to button clicks
        binding.timerImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mainViewModel.hamRecorder.isRunning()) {
                    mainViewModel.hamRecorder.stopRecord();
                    mainViewModel.ft8TransmitSignal.setActivated(false);
                    // [FIX] Сбросить ручной выбор при отключении передачи
                    // [FIX] Сбрасываем ручной выбор при остановке записи/передачи
                    if (mainViewModel.stationContext != null) {
                        mainViewModel.stationContext.userOverrideActive = false;
                        mainViewModel.stationContext.currentTarget = "";
                    }
                } else {
                    mainViewModel.hamRecorder.startRecord();
                }
            }
        });
        // Clear button
        binding.clearCallingListImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.clearFt8MessageList();
                callingListAdapter.notifyDataSetChanged();
                mainViewModel.mutable_Decoded_Counter.setValue(0);
            }
        });
        // Observe decoded message count
        mainViewModel.mutable_Decoded_Counter.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer integer) {
                binding.decoderCounterTextView.setText(
                        String.format(GeneralVariables.getStringFromResource(R.string.message_count_count)
                                , mainViewModel.currentDecodeCount, mainViewModel.ft8Messages.size()));
            }
        });

        mainViewModel.mutableFt8MessageList.observe(getViewLifecycleOwner(), new Observer<ArrayList<Ft8Message>>() {
            @Override
            public void onChanged(ArrayList<Ft8Message> messages) {
                callingListAdapter.notifyDataSetChanged();
                // Auto-scroll when list is near bottom
                if (callListRecyclerView.computeVerticalScrollRange()
                        - callListRecyclerView.computeVerticalScrollExtent()
                        - callListRecyclerView.computeVerticalScrollOffset() < 500) {
                    callListRecyclerView.scrollToPosition(callingListAdapter.getItemCount() - 1);
                }
            }
        });

        // Observe UTC time
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                binding.timerTextView.setText(UtcTimer.getTimeStr(aLong));
            }
        });

        // Observe time offset
        mainViewModel.mutableTimerOffset.observe(getViewLifecycleOwner(), new Observer<Float>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Float aFloat) {
                binding.timeOffsetTextView.setText(String.format(
                        getString(R.string.average_offset_seconds), aFloat));
            }
        });

        // Display Maidenhead grid
        GeneralVariables.mutableMyMaidenheadGrid.observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                binding.maidenheadTextView.setText(String.format(
                        getString(R.string.my_grid), s));
            }
        });

        // Observe decoding state
        mainViewModel.mutableIsDecoding.observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.isDecodingTextView.setText(getString(R.string.decoding));
                }
            }
        });

        // Observe decoding duration
        mainViewModel.ft8SignalListener.decodeTimeSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Long aLong) {
                binding.isDecodingTextView.setText(String.format(
                        getString(R.string.decoding_takes_milliseconds), aLong));
            }
        });

        // Show recording state with blink animation
        mainViewModel.mutableIsRecording.observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.timerImageButton.setImageResource(R.drawable.ic_baseline_mic_red_48);
                    binding.timerImageButton.setAnimation(AnimationUtils.loadAnimation(getContext()
                            , R.anim.view_blink));
                } else {
                    if (mainViewModel.hamRecorder.isRunning()) {
                        binding.timerImageButton.setImageResource(R.drawable.ic_baseline_mic_48);
                    } else {
                        binding.timerImageButton.setImageResource(R.drawable.ic_baseline_mic_off_48);
                    }
                    binding.timerImageButton.setAnimation(null);
                }
            }
        });

        // [NEW] Update RF frequency when band changes
        GeneralVariables.mutableBandChange.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer bandIndex) {
                updateRfFrequencyDisplay();
            }
        });

        // Toggle between simple and standard mode
        binding.callingListToolsBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GeneralVariables.simpleCallItemMode = !GeneralVariables.simpleCallItemMode;
                callListRecyclerView.setAdapter(callingListAdapter);
                callingListAdapter.notifyDataSetChanged();
                callListRecyclerView.scrollToPosition(callingListAdapter.getItemCount() - 1);
                if (GeneralVariables.simpleCallItemMode) {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_simple_mode));
                } else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_standard_mode));
                }
            }
        });

        return binding.getRoot();
    }

    /**
     * [NEW] Update RF frequency display in MHz
     */
    private void updateRfFrequencyDisplay() {
        if (rfFrequencyTextView == null) return;
        double freqMhz = GeneralVariables.band / 1_000_000.0;
        String freqStr = String.format(java.util.Locale.US, "%.3f MHz", freqMhz);
        rfFrequencyTextView.setText(freqStr);
    }

    /**
     * Setup RecyclerView swipe actions
     */
    private void initRecyclerViewAction() {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.ANIMATION_TYPE_DRAG
                , ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder
                    , @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            //@RequiresApi(api = Build.VERSION_CODES.N)
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                if (direction == ItemTouchHelper.START) { // Call
                    Ft8Message message = callingListAdapter.getMessageByViewHolder(viewHolder);
                    if (message != null) {
                        // Target cannot be self
                        if (!message.getCallsignFrom().equals("<...>")
                                //&& !message.getCallsignFrom().equals(GeneralVariables.myCallsign)
                                && !GeneralVariables.checkIsMyCallsign(message.getCallsignFrom())
                                && !(message.i3 == 0 && (message.n3 == 0 || message.n3 == 5))) { // Telemetry and free text cannot be called
                            doCallNow(message);
                        } else {
                            callingListAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
                        }
                    }
                }
                if (direction == ItemTouchHelper.END) { // Delete
                    callingListAdapter.deleteMessage(viewHolder.getAdapterPosition());
                    callingListAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY
                    , int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                // Draw call background icon
                final Drawable callIcon = ContextCompat.getDrawable(requireActivity()
                        , R.drawable.ic_baseline_send_red_48);
                final Drawable delIcon = ContextCompat.getDrawable(requireActivity()
                        , R.drawable.log_item_delete_icon);
                final Drawable background = new ColorDrawable(Color.LTGRAY);
                Ft8Message message = callingListAdapter.getMessageByViewHolder(viewHolder);
                if (message == null) {
                    return;
                }
                if (message.getCallsignFrom().equals("<...>")) { // If message cannot be called, do not show icon
                    return;
                }
                Drawable icon;
                if (dX > 0) {
                    icon = delIcon;
                } else {
                    icon = callIcon;
                }
                View itemView = viewHolder.itemView;
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
        }).attachToRecyclerView(binding.callingListRecyclerView);
    }

    /**
     * Immediately call the initiator
     * @param message Message to respond to
     * @return true if call initiated
     */
//@RequiresApi(api = Build.VERSION_CODES.N)
    private boolean doCallNow(Ft8Message message) {

        mainViewModel.addFollowCallsign(message.getCallsignFrom());

        // === [FIX] Устанавливаем ручной выбор! ===
        // Это критически важно, чтобы DecisionEngine знал, что это ручной выбор
        if (mainViewModel.stationContext != null) {
            mainViewModel.stationContext.userOverrideActive = true;
            mainViewModel.stationContext.currentTarget = message.getCallsignFrom();
            mainViewModel.stationContext.subState = StationState.OperatingSubState.SEEKING;
            Log.d(TAG, "[MANUAL] User selected target: " + message.getCallsignFrom());
        }
        // ================================

        if (!mainViewModel.ft8TransmitSignal.isActivated()) {
            mainViewModel.ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(message); // Add message to follow list
        }
        // Call the initiator
        Log.d(TAG, "[TX_TRIGGER] CallingListFragment.doCallNow() → setTransmit(" + message.getCallsignFrom() + ")");
        mainViewModel.ft8TransmitSignal.setTransmit(message.getFromCallTransmitCallsign()
                , 1, message.extraInfo);
        Log.d(TAG, "[TX_TRIGGER] CallingListFragment.doCallNow() → transmitNow()");
        mainViewModel.ft8TransmitSignal.transmitNow();
        GeneralVariables.resetLaunchSupervision(); // Reset auto supervision
        navigateToMyCallFragment(); // Navigate to transmit interface
        return true;
    }

    /**
     * [NEW] Queue specific FT8 dialogue state using EXISTING transmit logic
     * Routes through ft8TransmitSignal.setTransmit() with specified functionOrder
     * @param message Source message for callsign/frequency data
     * @param state FT8 dialogue state (1-4) - maps directly to functionOrder
     * Created: 2026-05-14 per user request - MINIMAL CHANGE ONLY
     */
    private void queueStateUsingExistingLogic(Ft8Message message, int state) {
        // Validate state range (FT8 protocol steps 1-4)
        if (state < 1 || state > 4) {
            Log.w(TAG, "Invalid FT8 state requested: " + state);
            return;
        }

        String targetCallsign = message.getCallsignFrom();

        // [FIX] Use EXISTING manual override mechanism (same as doCallNow)
        if (mainViewModel.stationContext != null) {
            mainViewModel.stationContext.userOverrideActive = true;
            mainViewModel.stationContext.currentTarget = targetCallsign;
            mainViewModel.stationContext.subState = StationState.OperatingSubState.SEEKING;
            Log.d(TAG, "[QUEUE STATE " + state + "] Manual override for: " + targetCallsign);
        }

        // [FIX] Activate transmit signal using EXISTING logic
        if (!mainViewModel.ft8TransmitSignal.isActivated()) {
            mainViewModel.ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(message);
        }

        // [CRITICAL] Use EXISTING setTransmit() with functionOrder = state
        // This is the SAME call used by auto-logic, just with user-selected step
        Log.d(TAG, "[TX_TRIGGER] CallingListFragment.queueStateUsingExistingLogic() → setTransmit(" + targetCallsign + ", state=" + state + ")");
        mainViewModel.ft8TransmitSignal.setTransmit(
                message.getFromCallTransmitCallsign(),  // Existing helper method
                state,                                   // functionOrder: 1=CALL, 2=REPORT, 3=R-REPORT, 4=RR73
                message.extraInfo);                      // Existing extra info

        // [FIX] Trigger immediate transmit using EXISTING method
        Log.d(TAG, "[TX_TRIGGER] CallingListFragment.queueStateUsingExistingLogic() → transmitNow()");
        mainViewModel.ft8TransmitSignal.transmitNow();
        GeneralVariables.resetLaunchSupervision();

        // Navigate to transmit interface (existing behavior)
        navigateToMyCallFragment();

        // Show minimal feedback
        String stateLabel;
        switch (state) {
            case 1: stateLabel = "CALL"; break;
            case 2: stateLabel = "REPORT"; break;
            case 3: stateLabel = "R-REPORT"; break;
            case 4: stateLabel = "RR73"; break;
            default: stateLabel = "STATE";
        }
        Toast.makeText(getContext(), "Queued " + stateLabel + " for " + targetCallsign, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "[QUEUE] Transmission queued: " + targetCallsign + " step=" + state);
    }

    /**
     * Navigate to log query interface
     * @param callsign Callsign to query
     */
    private void navigateToLogFragment(String callsign){
        mainViewModel.queryKey = callsign; // Submit callsign as query key
        NavController navController = Navigation.findNavController(requireActivity()
                , R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_calling_list_to_menu_nav_history); // Navigate to log
    }

    /**
     * Navigate to transmit interface
     */
    private void navigateToMyCallFragment() {
        NavController navController = Navigation.findNavController(requireActivity()
                , R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_calling_list_to_menu_nav_mycalling); // Navigate to transmit interface
    }

    /**
     * Create context menu with FT8 state queue options
     * @param menu Context menu to populate
     * @param v View that triggered the menu
     * @param menuInfo Menu info
     */
    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        // [OBSOLETE] Original menu items - preserved for reference
        // menu.add(0, 0, 0, "Follow TO");
        // menu.add(0, 1, 1, "Call TO");
        // menu.add(0, 2, 2, "Follow FROM");
        // menu.add(0, 3, 3, "Call FROM");
        // menu.add(0, 4, 4, "Reply");
        // menu.add(0, 5, 5, "QRZ TO");
        // menu.add(0, 6, 6, "QRZ FROM");
        // menu.add(0, 7, 7, "Log TO");
        // menu.add(0, 8, 8, "Log FROM");

        // [NEW] Updated menu with FT8 state options - MINIMAL ADDITION
        menu.setHeaderTitle("Station Actions");
        menu.add(0, MENU_FOLLOW_TO, 0, "Follow TO");
        menu.add(0, MENU_CALL_TO, 1, "Call TO");
        menu.add(0, MENU_FOLLOW_FROM, 2, "Follow FROM");
        menu.add(0, MENU_CALL_FROM, 3, "Call FROM");
        menu.add(0, MENU_REPLY, 4, "Reply");
        menu.add(0, MENU_QRZ_TO, 5, "QRZ TO");
        menu.add(0, MENU_QRZ_FROM, 6, "QRZ FROM");
        menu.add(0, MENU_LOG_TO, 7, "Log TO");
        menu.add(0, MENU_LOG_FROM, 8, "Log FROM");
        // [NEW] FT8 state menu items - route through existing transmit logic
        menu.add(0, MENU_STATE_1, 9, "State 1 (CALL)");
        menu.add(0, MENU_STATE_2, 10, "State 2 (REPORT)");
        menu.add(0, MENU_STATE_3, 11, "State 3 (R-REPORT)");
        menu.add(0, MENU_STATE_4, 12, "State 4 (RR73)");
    }

    /**
     * Handle context menu item selection
     * @param item Selected menu item
     * @return true if handled
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {

        //Ft8Message ft8Message = (Ft8Message) item.getActionView().getTag();
        int position = (int) item.getActionView().getTag();
        Ft8Message ft8Message = callingListAdapter.getMessageByPosition(position);
        if (ft8Message == null) return super.onContextItemSelected(item);

        switch (item.getItemId()) {
            case MENU_FOLLOW_TO:
                Log.d(TAG, "Follow: " + ft8Message.getCallsignTo());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignTo());
                GeneralVariables.transmitMessages.add(ft8Message); // Add message to follow list
                break;
            case MENU_CALL_TO: // Timing opposite to sender!!!
                Log.d(TAG, "Call: " + ft8Message.getCallsignTo());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignTo());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message); // Add message to follow list
                    GeneralVariables.resetLaunchSupervision(); // Reset auto supervision
                }
                // Call the target station
                Log.d(TAG, "[TX_TRIGGER] CallingListFragment.onContextItemSelected(MENU_CALL_TO) → setTransmit(" + ft8Message.getCallsignTo() + ")");
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getToCallTransmitCallsign()
                        , 1, ft8Message.extraInfo);
                Log.d(TAG, "[TX_TRIGGER] CallingListFragment.onContextItemSelected(MENU_CALL_TO) → transmitNow()");
                mainViewModel.ft8TransmitSignal.transmitNow();

                navigateToMyCallFragment(); // Navigate to transmit interface
                break;
            case MENU_FOLLOW_FROM:
                Log.d(TAG, "Follow: " + ft8Message.getCallsignFrom());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                GeneralVariables.transmitMessages.add(ft8Message); // Add message to follow list
                break;
            case MENU_CALL_FROM:
                Log.d(TAG, "Call: " + ft8Message.getCallsignFrom());
                doCallNow(ft8Message);
                break;

            case MENU_REPLY:
                Log.d(TAG, "Reply: " + ft8Message.getCallsignFrom());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message); // Add message to follow list
                }
                // Call the initiator
                Log.d(TAG, "[TX_TRIGGER] CallingListFragment.onContextItemSelected(MENU_REPLY) → setTransmit(" + ft8Message.getCallsignFrom() + ")");
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign()
                        , -1, ft8Message.extraInfo);
                Log.d(TAG, "[TX_TRIGGER] CallingListFragment.onContextItemSelected(MENU_REPLY) → transmitNow()");
                mainViewModel.ft8TransmitSignal.transmitNow();
                GeneralVariables.resetLaunchSupervision(); // Reset auto supervision
                navigateToMyCallFragment(); // Navigate to transmit interface
                break;
            case MENU_QRZ_TO: // QRZ for TO callsign
                showQrzFragment(ft8Message.getCallsignTo());
                break;
            case MENU_QRZ_FROM: // QRZ for FROM callsign
                showQrzFragment(ft8Message.getCallsignFrom());
                break;
            case MENU_LOG_TO: // Query log for TO callsign
                navigateToLogFragment(ft8Message.getCallsignTo());
                break;
            case MENU_LOG_FROM: // Query log for FROM callsign
                navigateToLogFragment(ft8Message.getCallsignFrom());
                break;

            // [NEW] FT8 state handlers - MINIMAL: route through existing transmit logic
            case MENU_STATE_1:
                Log.d(TAG, "Queue State 1 (CALL) for: " + ft8Message.getCallsignFrom());
                queueStateUsingExistingLogic(ft8Message, 1);
                break;
            case MENU_STATE_2:
                Log.d(TAG, "Queue State 2 (REPORT) for: " + ft8Message.getCallsignFrom());
                queueStateUsingExistingLogic(ft8Message, 2);
                break;
            case MENU_STATE_3:
                Log.d(TAG, "Queue State 3 (R-REPORT) for: " + ft8Message.getCallsignFrom());
                queueStateUsingExistingLogic(ft8Message, 3);
                break;
            case MENU_STATE_4:
                Log.d(TAG, "Queue State 4 (RR73) for: " + ft8Message.getCallsignFrom());
                queueStateUsingExistingLogic(ft8Message, 4);
                break;
        }

        return super.onContextItemSelected(item);
    }

    /**
     * Show QRZ query interface
     * @param callsign Callsign to query
     */
    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity().getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null; // Assert not null
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}