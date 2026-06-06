//MyCallingFragment.java
package com.bg7yoz.ft8cn.ui;
/**
 * Calling interface.
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
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
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
import com.bg7yoz.ft8cn.databinding.FragmentMyCallingBinding;
import com.bg7yoz.ft8cn.ft8transmit.FunctionOfTransmit;
import com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.Locale;


public class MyCallingFragment extends Fragment {
    private static final String TAG = "MyCallingFragment";
    private FragmentMyCallingBinding binding;
    private MainViewModel mainViewModel;

    private RecyclerView transmitRecycleView;

    private CallingListAdapter transmitCallListAdapter;

    private FunctionOrderSpinnerAdapter functionOrderSpinnerAdapter;


    static {
        try {
            // Attempt to load library based on setting
            boolean dxMode = com.bg7yoz.ft8cn.GeneralVariables.acceptDxCalls;
            String libName = dxMode ? "ft8cn_dx" : "ft8cn_std";
            System.loadLibrary(libName);
        } catch (UnsatisfiedLinkError e) {
            // Fallback: try to load standard
            try {
                System.loadLibrary("ft8cn_std");
            } catch (UnsatisfiedLinkError e2) {
                // If failed, try old name for compatibility
                try {
                    System.loadLibrary("ft8cn");
                } catch (UnsatisfiedLinkError e3) {
                    android.util.Log.e("ReBuildSignal", "Failed to load any native library", e3);
                }
            }
        }
    }


    /**
     * Call the initiator immediately
     *
     * @param message message
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    private void doCallNow(Ft8Message message) {
        mainViewModel.addFollowCallsign(message.getCallsignFrom());
        GeneralVariables.transmitMessages.add(message);

        // [FIX] Use new method that updates state machine
        Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.doCallNow() → manualCallStation(" + message.getCallsignFrom() + ")");
        mainViewModel.manualCallStation(
                message.getCallsignFrom(),
                message.i3,
                message.n3,
                message.extraInfo,
                (long) message.freq_hz,
                message.snr
        );

        GeneralVariables.resetLaunchSupervision();
    }


    /**
     * Menu options
     *
     * @param item menu item
     * @return is selected
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        //Ft8Message ft8Message = (Ft8Message) item.getActionView().getTag();

        int position = (int) item.getActionView().getTag();
        Ft8Message ft8Message = transmitCallListAdapter.getMessageByPosition(position);
        if (ft8Message == null) return super.onContextItemSelected(item);
        ;

        GeneralVariables.resetLaunchSupervision();//reset auto supervision
        switch (item.getItemId()) {
            case 1://sequence opposite to sender!!!
                Log.d(TAG, "Calling: " + ft8Message.getCallsignTo());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                }
                Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.onContextItemSelected(case 1) → setTransmit(" + ft8Message.getCallsignTo() + ")");
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getToCallTransmitCallsign()
                        , 1, ft8Message.extraInfo);
                Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.onContextItemSelected(case 1) → transmitNow()");
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 3:
                Log.d(TAG, "Calling: " + ft8Message.getCallsignFrom());
                doCallNow(ft8Message);
                //if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                //    mainViewModel.ft8TransmitSignal.setActivated(true);
                // }
                // mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign()
                //        , 1, ft8Message.extraInfo);
                //mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 4://reply
                Log.d(TAG, "Reply: " + ft8Message.getCallsignFrom());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message);//add message to follow list
                }
                //call initiator
                Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.onContextItemSelected(case 4 REPLY) → setTransmit(" + ft8Message.getCallsignFrom() + ")");
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign()
                        , -1, ft8Message.extraInfo);
                Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.onContextItemSelected(case 4 REPLY) → transmitNow()");
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 5://to QRZ
                showQrzFragment(ft8Message.getCallsignTo());
                break;
            case 6://from QRZ
                showQrzFragment(ft8Message.getCallsignFrom());
                break;
            case 7://query to log
                navigateToLogFragment(ft8Message.getCallsignTo());
                break;
            case 8://query from log
                navigateToLogFragment(ft8Message.getCallsignFrom());
                break;
            case 9: // [NEW] Call TARGET MYCALL SWR
                Log.d(TAG, "Call SWR to: " + ft8Message.getCallsignTo());
                Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.onContextItemSelected(case 9 SWR) → sendCustomTransmission(" + ft8Message.getCallsignTo() + ", SWR)");
                mainViewModel.sendCustomTransmission(ft8Message.getCallsignTo(), "SWR");
                break;

            case 10: // [NEW] Call TARGET MYCALL RSWR
                Log.d(TAG, "Call RSWR to: " + ft8Message.getCallsignTo());
                Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.onContextItemSelected(case 10 RSWR) → sendCustomTransmission(" + ft8Message.getCallsignTo() + ", RSWR)");
                mainViewModel.sendCustomTransmission(ft8Message.getCallsignTo(), "RSWR");
                break;

        }

        return super.onContextItemSelected(item);
    }
    /**
     * Navigate to log query interface
     * @param callsign callsign
     */
    private void navigateToLogFragment(String callsign){
        mainViewModel.queryKey=callsign;//submit callsign as keyword
        NavController navController = Navigation.findNavController(requireActivity()
                , R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_mycalling_to_menu_nav_history);//navigate to log
    }
    /**
     * Query QRZ information
     *
     * @param callsign callsign
     */
    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity().getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;//assert not null
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }

    /**
     * [NEW] Update RF frequency display in MHz
     */
    private void updateRfFrequencyDisplay() {
        if (binding == null || binding.rfFrequencyTextView == null) return;
        double freqMhz = GeneralVariables.band / 1_000_000.0;
        String freqStr = String.format(Locale.US, "%.3f MHz", freqMhz);
        binding.rfFrequencyTextView.setText(freqStr);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentMyCallingBinding.inflate(inflater, container, false);

        // [NEW] Initialize RF frequency display
        updateRfFrequencyDisplay();

        //show spectrum when landscape
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.messageSpectrumView.run(mainViewModel, this);
        }


        //transmit message list
        functionOrderSpinnerAdapter = new FunctionOrderSpinnerAdapter(requireContext(), mainViewModel);
        binding.functionOrderSpinner.setAdapter(functionOrderSpinnerAdapter);
        functionOrderSpinnerAdapter.notifyDataSetChanged();


        //follow message list
        transmitRecycleView = binding.transmitRecycleView;
        transmitCallListAdapter = new CallingListAdapter(this.getContext(), mainViewModel
                , GeneralVariables.transmitMessages, CallingListAdapter.ShowMode.MY_CALLING);
        transmitRecycleView.setLayoutManager(new LinearLayoutManager(requireContext()));
        transmitRecycleView.setAdapter(transmitCallListAdapter);


        transmitCallListAdapter.notifyDataSetChanged();


        //set message list swipe for quick call
        initRecyclerViewAction();
        //menu
        requireActivity().registerForContextMenu(transmitRecycleView);

        //show UTC time
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                binding.timerTextView.setText(UtcTimer.getTimeStr(aLong));
            }
        });

// === NEW: Show UTC delay with sign (between timer and speaker icon) ===
// Update 2 times per second for smooth display
        final Handler utcDelayHandler = new Handler(Looper.getMainLooper());
        utcDelayHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (binding != null && binding.utcDelayTextView != null && getActivity() != null) {
                    // %+d automatically adds + or - sign before number
                    binding.utcDelayTextView.setText(String.format(Locale.US, "%+d", UtcTimer.delay));

                    // [NEW] Update sequential slot display
                    if (binding.seqTextView != null) {
                        binding.seqTextView.setText(String.format(Locale.US, "seq: %d", UtcTimer.getNowSequential()));
                    }
                    // [/NEW]

                    // Use handler reference, not 'this'
                    utcDelayHandler.postDelayed(this, 500);
                }
            }
        }, 500);
// === END NEW ===

        //show transmit frequency
        GeneralVariables.mutableBaseFrequency.observe(getViewLifecycleOwner(), new Observer<Float>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Float aFloat) {
                binding.baseFrequencyTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.sound_frequency_is), aFloat));
            }
        });

        // [NEW] Update RF frequency when band changes
        GeneralVariables.mutableBandChange.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer bandIndex) {
                updateRfFrequencyDisplay();
            }
        });


        // [FIX] Declare observer BEFORE using it in observe() calls
        // observe transmit state button changes
        Observer<Boolean> transmittingObserver = new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (mainViewModel.ft8TransmitSignal.isTransmitting()) {
                    binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_send_red_48);
                    binding.setTransmitImageButton.setAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.view_blink));
                } else {
                    //recorder object must also be running to have transmit state
                    if (mainViewModel.ft8TransmitSignal.isActivated() && mainViewModel.hamRecorder.isRunning()) {
                        binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_send_white_48);
                    } else {
                        binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_cancel_schedule_send_off);
                    }
                    binding.setTransmitImageButton.setAnimation(null);
                }

                //pause play button
                if (mainViewModel.ft8TransmitSignal.isTransmitting()) {
                    binding.pauseTransmittingImageButton.setImageResource(R.drawable.ic_baseline_pause_circle_outline_24);
                    binding.pauseTransmittingImageButton.setVisibility(View.VISIBLE);
                } else {
                    binding.pauseTransmittingImageButton.setVisibility(View.GONE);
                    binding.pauseTransmittingImageButton.setImageResource(R.drawable.ic_baseline_pause_disable_circle_outline_24);
                }
            }
        };

        //show transmit state
        // [FIX] Now observer is defined, safe to use
        mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observe(getViewLifecycleOwner(), transmittingObserver);
        mainViewModel.ft8TransmitSignal.mutableIsActivated.observe(getViewLifecycleOwner(), transmittingObserver);

        //pause button
        binding.pauseTransmittingImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.ft8TransmitSignal.setTransmitting(false);
                GeneralVariables.resetLaunchSupervision();//reset auto supervision
            }
        });

        //monitor command program
        mainViewModel.ft8TransmitSignal.mutableFunctions.observe(getViewLifecycleOwner()
                , new Observer<ArrayList<FunctionOfTransmit>>() {
                    @Override
                    public void onChanged(ArrayList<FunctionOfTransmit> functionOfTransmits) {
                        functionOrderSpinnerAdapter.notifyDataSetChanged();
                    }
                });

        //observe command sequence number changes
        mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (mainViewModel.ft8TransmitSignal.functionList.size() < 6) {
                    binding.functionOrderSpinner.setSelection(0);
                } else {
                    binding.functionOrderSpinner.setSelection(integer - 1);
                }
            }
        });

        //set event when command sequence number is selected
        binding.functionOrderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (mainViewModel.ft8TransmitSignal.functionList.size() > 1) {
                    mainViewModel.ft8TransmitSignal.setCurrentFunctionOrder(i + 1);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });


        //show current target callsign
        mainViewModel.ft8TransmitSignal.mutableToCallsign.observe(getViewLifecycleOwner(), new Observer<TransmitCallsign>() {
            @Override
            public void onChanged(TransmitCallsign transmitCallsign) {
                if (GeneralVariables.toModifier!=null) {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign)
                            , transmitCallsign.callsign+" "+GeneralVariables.toModifier));
                }else {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign)
                            , transmitCallsign.callsign));
                }
            }
        });

        //show current transmit sequence
        mainViewModel.ft8TransmitSignal.mutableSequential.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer integer) {
                binding.transmittingSequentialTextView.setText(
                        String.format(GeneralVariables.getStringFromResource(R.string.transmission_sequence)
                                , integer));
            }
        });

        // [OLD CODE - COMMENTED OUT]
        /*
        //set transmit button - direct call to restTransmitting(), bypasses state machine
        binding.setTransmitImageButton.setOnClickListener(new View.OnClickListener() {
            //@RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onClick(View view) {
                //if
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.restTransmitting();
                }
                mainViewModel.ft8TransmitSignal.setActivated(!mainViewModel.ft8TransmitSignal.isActivated());
                GeneralVariables.resetLaunchSupervision();//reset auto supervision
            }
        });
        */

        // [NEW CODE] Request through ViewModel, let state machine decide
        // This ensures all transmissions go through executeAction() for proper logging and state sync
        // [NEW CODE] Request through ViewModel
        binding.setTransmitImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean newState = !mainViewModel.ft8TransmitSignal.isActivated();
                mainViewModel.ft8TransmitSignal.setActivated(newState);
                GeneralVariables.resetLaunchSupervision();
                Log.d(TAG, "[UI_CLICK] Transmit " + (newState ? "enabled" : "disabled"));
            }
        });

        //observe transmit message list changes
        //mainViewModel.mutableTransmitMessages.observe(getViewLifecycleOwner(), new Observer<ArrayList<Ft8Message>>() {
        mainViewModel.mutableTransmitMessagesCount.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer count) {
                binding.decoderCounterTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.message_count)
                        , GeneralVariables.transmitMessages.size()));
                //if (count == 0) {
                transmitCallListAdapter.notifyDataSetChanged();
                //} else {
                //    transmitCallListAdapter.notifyItemInserted(
                //            GeneralVariables.transmitMessages.size() - count);
                //}

                //when list bottom has some extra space, auto scroll up
                if (transmitRecycleView.computeVerticalScrollRange()
                        - transmitRecycleView.computeVerticalScrollExtent()
                        - transmitRecycleView.computeVerticalScrollOffset() < 300) {
                    transmitRecycleView.scrollToPosition(transmitCallListAdapter.getItemCount() - 1);
                }
            }
        });

        //clear transmit message list
        binding.clearMycallListImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.clearTransmittingMessage();
            }
        });

        //reset to CQ button
        binding.resetToCQImageView.setOnClickListener(new View.OnClickListener() {
            //@RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onClick(View view) {
                Log.d(TAG, "[TX_TRIGGER] MyCallingFragment.resetToCQImageView.onClick() → resetToCQ()");
                mainViewModel.ft8TransmitSignal.resetToCQ();
                GeneralVariables.resetLaunchSupervision();//reset auto supervision
            }
        });
        //free text input field limit operations
        binding.transFreeTextEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                mainViewModel.ft8TransmitSignal.setFreeText(editable.toString().toUpperCase());
            }
        });
        binding.resetToCQImageView.setLongClickable(true);
        binding.resetToCQImageView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                mainViewModel.setTransmitIsFreeText(!mainViewModel.getTransitIsFreeText());
                showFreeTextEdit();
                return true;
            }
        });

        binding.mycallToolsBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GeneralVariables.simpleCallItemMode=!GeneralVariables.simpleCallItemMode;
                transmitRecycleView.setAdapter(transmitCallListAdapter);
                transmitCallListAdapter.notifyDataSetChanged();
                transmitRecycleView.scrollToPosition(transmitCallListAdapter.getItemCount() - 1);
                if (GeneralVariables.simpleCallItemMode){
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_simple_mode));
                }else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_standard_mode));
                }
            }
        });


        showFreeTextEdit();
        return binding.getRoot();
    }

    private void showFreeTextEdit() {
        if (mainViewModel.getTransitIsFreeText()) {
            binding.transFreeTextEdit.setVisibility(View.VISIBLE);
            binding.functionOrderSpinner.setVisibility(View.GONE);
        } else {
            binding.transFreeTextEdit.setVisibility(View.GONE);
            binding.functionOrderSpinner.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Set list swipe actions
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
                if (direction == ItemTouchHelper.START) {
                    Ft8Message message = transmitCallListAdapter.getMessageByViewHolder(viewHolder);
                    if (message != null) {
                        //call target cannot be self
                        if (!message.getCallsignFrom().equals("<...>")
                                //&& !message.getCallsignFrom().equals(GeneralVariables.myCallsign)
                                && !GeneralVariables.checkIsMyCallsign(message.getCallsignFrom())
                                && !(message.i3 == 0 && message.n3 == 0)) {
                            doCallNow(message);
                        }
                    }
                    transmitCallListAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
                }
                if (direction == ItemTouchHelper.END) {//delete
                    transmitCallListAdapter.deleteMessage(viewHolder.getAdapterPosition());
                    transmitCallListAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                }
            }


            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                //make call background icon display
                Drawable callIcon = ContextCompat.getDrawable(requireActivity(), R.drawable.ic_baseline_send_red_48);
                Drawable delIcon = ContextCompat.getDrawable(requireActivity(), R.drawable.log_item_delete_icon);
                Drawable background = new ColorDrawable(Color.LTGRAY);
                Ft8Message message = transmitCallListAdapter.getMessageByViewHolder(viewHolder);
                if (message == null) {
                    return;
                }
                if (message.getCallsignFrom().equals("<...>")) {//if message cannot be called, do not show icon
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
        }).attachToRecyclerView(binding.transmitRecycleView);
    }
}