// [FIX-RECYCLER-ICONS 2026-05-17] Added explicit icon state reset to fix RecyclerView recycling bug
// Issue: Icons D/C/I from previous list items could "leak" to new items due to ViewHolder reuse
// Fix: Always set visibility to GONE first, then VISIBLE only if condition is true

package com.bg7yoz.ft8cn.ui;

/**
 * Message list Adapter. Used for decode interface, calling interface, grid tracker.
 * Different periods have different backgrounds. Total 4 background colors.
 * @author BGY70Z
 * @date 2023-03-20
 *
 * [FIX-RECYCLER-ICONS 2026-05-17] Modified setFromDxcc/setToDxcc to prevent icon state leakage
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.Locale;

public class CallingListAdapter extends RecyclerView.Adapter<CallingListAdapter.CallingListItemHolder> {
    public enum ShowMode{CALLING_LIST,MY_CALLING,TRACKER}
    private static final String TAG = "CallingListAdapter";
    private final MainViewModel mainViewModel;
    private final ArrayList<Ft8Message> ft8MessageArrayList;
    private final Context context;

    private final ShowMode showMode;
    private View.OnClickListener onItemClickListener;

    private final View.OnCreateContextMenuListener menuListener=new View.OnCreateContextMenuListener() {
        @Override
        public void onCreateContextMenu(ContextMenu contextMenu, View view, ContextMenu.ContextMenuInfo contextMenuInfo) {

            //view.setTag(ft8Message);//pass message object to upper level interface
            int postion= (int) view.getTag();
            if (postion==-1) return;
            if (postion>ft8MessageArrayList.size()-1) return;
            Ft8Message ft8Message=ft8MessageArrayList.get(postion);

            //add menu parameters i1:group, i2:id value, i3:display order
            if (!ft8Message.getCallsignTo().contains("...")//target cannot be self
                    //&& !ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)
                    && !GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())
                    && !(ft8Message.i3==0&&ft8Message.n3==0)) {
                if (!ft8Message.checkIsCQ()) {
                    if (showMode==ShowMode.CALLING_LIST) {//in message list can show this menu
                        contextMenu.add(0, 0, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.tracking_receiver)
                                        , ft8Message.getCallsignTo(), ft8Message.toWhere))
                                .setActionView(view);
                    }
                    if (!mainViewModel.ft8TransmitSignal.isSynFrequency()) {//if same frequency, will affect transmitter!!!
                        contextMenu.add(0, 1, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.calling_receiver)
                                        , ft8Message.getCallsignTo(), ft8Message.toWhere))
                                .setActionView(view);

                        // [NEW] Custom quick-transmit options for Target station
                        contextMenu.add(0, 9, 0, "Call SWR")
                                .setActionView(view);
                        contextMenu.add(0, 10, 0, "Call RSWR")
                                .setActionView(view);
                        // [/NEW]
                    }
                    //means calling me, add reply menu
                    //if (ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)) {
                    if (GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())) {
                        contextMenu.add(0, 4, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.reply_to)
                                        , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                                .setActionView(view);

                    }
                    if (showMode!=ShowMode.TRACKER) {
                        contextMenu.add(0, 5, 0
                                , String.format(GeneralVariables.getStringFromResource(R.string.qsl_qrz_confirmation_s)
                                        , ft8Message.getCallsignTo())).setActionView(view);
                    }

                    //add query log
                    contextMenu.add(0, 7, 0
                            , String.format(GeneralVariables.getStringFromResource(R.string.qsl_query_log_menu)
                                    , ft8Message.getCallsignTo())).setActionView(view);

                }
            }

            if (!ft8Message.getCallsignFrom().contains("...")
                    //&& !ft8Message.getCallsignFrom().equals(GeneralVariables.myCallsign)
                    && !GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignFrom())
                    && !(ft8Message.i3==0&&ft8Message.n3==0)) {
                if (showMode==ShowMode.CALLING_LIST) {//in message list can show this menu
                    contextMenu.add(1, 2, 0, String.format(
                                    GeneralVariables.getStringFromResource(R.string.tracking)
                                    , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                            .setActionView(view);
                }
                contextMenu.add(1, 3, 0, String.format(
                                GeneralVariables.getStringFromResource(R.string.calling)
                                , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                        .setActionView(view);
                if (showMode!=ShowMode.TRACKER) {
                    contextMenu.add(1, 6, 0
                            , String.format(GeneralVariables.getStringFromResource(R.string.qsl_qrz_confirmation_s)
                                    , ft8Message.getCallsignFrom())).setActionView(view);
                }

                //add query log
                contextMenu.add(0, 8, 0
                        , String.format(GeneralVariables.getStringFromResource(R.string.qsl_query_log_menu)
                                , ft8Message.getCallsignFrom())).setActionView(view);
            }

        }
    };



    public CallingListAdapter(Context context, MainViewModel mainViewModel
            , ArrayList<Ft8Message> messages, ShowMode showMode) {
        this.mainViewModel = mainViewModel;
        this.context = context;
        this.showMode=showMode;
        ft8MessageArrayList = messages;
    }

    @NonNull
    @Override
    public CallingListItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        View view ;
        if (GeneralVariables.simpleCallItemMode) {
            view = layoutInflater.inflate(R.layout.call_list_holder_simple_item, parent, false);
        }else {
            view = layoutInflater.inflate(R.layout.call_list_holder_item, parent, false);
        }
        return new CallingListItemHolder(view,onItemClickListener,menuListener);
    }

    /**
     * Delete message
     *
     * @param position position in list
     */
    public void deleteMessage(int position) {
        if (position >= 0) {
            ft8MessageArrayList.remove(position);
        }
    }

    public Ft8Message getMessageByPosition(int position){
        if (ft8MessageArrayList==null) return null;
        if (position<0) return null;
        if (position>ft8MessageArrayList.size()-1) return null;
        return ft8MessageArrayList.get(position);
    }

    /**
     * Get message by holder
     *
     * @param holder holder
     * @return ft8message
     */
    public Ft8Message getMessageByViewHolder(RecyclerView.ViewHolder holder) {
        if (holder.getAdapterPosition() == -1) {
            return null;
        }
        return ft8MessageArrayList.get(holder.getAdapterPosition());
    }

    @SuppressLint("ResourceAsColor")
    @Override
    public void onBindViewHolder(@NonNull CallingListItemHolder holder, int position) {
        holder.callListHolderConstraintLayout.setTag(position);//set layout tag for message positioning
        holder.ft8Message = ft8MessageArrayList.get(position);
        holder.showMode = showMode;//determine if message list or follow message list
        holder.isSyncFreq = mainViewModel.ft8TransmitSignal.isSynFrequency();//if same freq transmit, do not show call receiver

        // === [FIX] Null-safe setText for all TextViews ===
        safeSetText(holder.callingUtcTextView, UtcTimer.getTimeHHMMSS(holder.ft8Message.utcTime));
        safeSetText(holder.callingListSequenceTextView, holder.ft8Message.getSequence() == 0 ? "0" : "1");
        // === END FIX ===

        holder.isWeakSignalImageView.setVisibility(holder.ft8Message.isWeakSignal ? View.VISIBLE:View.INVISIBLE);

        if (showMode==ShowMode.MY_CALLING) {//in calling interface
            if (holder.callingListSequenceTextView != null) {
                holder.callingListSequenceTextView.setTextColor(context.getColor(R.color.follow_call_text_color));
            }
        }

        //distinguish colors by 4 sequences within 1 minute
        switch (holder.ft8Message.getSequence4()) {
            case 0:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_0_style);
                break;
            case 1:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_1_style);
                break;
            case 2:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_2_style);
                break;
            case 3:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_3_style);
                break;
        }

        // === [FIX] Null-safe setText ===
        safeSetText(holder.callingListIdBTextView, holder.ft8Message.getdB());
        safeSetText(holder.callListDtTextView, holder.ft8Message.getDt());
        // === END FIX ===

        if (holder.callListDtTextView != null) {
            if (holder.ft8Message.time_sec > 1.0f || holder.ft8Message.time_sec < -0.05) {
                holder.callListDtTextView.setTextColor(context.getResources().getColor(
                        R.color.message_in_my_call_text_color));
            } else {
                holder.callListDtTextView.setTextColor(context.getResources().getColor(
                        R.color.text_view_color));
            }
        }

        // === [FIX] Null-safe setText ===
        safeSetText(holder.callingListFreqTextView, holder.ft8Message.getFreq_hz());
        // === END FIX ===

        //check if callsign was QSLed, get existence in holder.otherBandIsQso
        setQueryHolderQSL_Callsign(holder);

        //if message related to my callsign
        if (holder.ft8Message.inMyCall()) {
            if (holder.callListMessageTextView != null) {
                holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                        R.color.message_in_my_call_text_color));
            }
        } else if (holder.otherBandIsQso) {
            //set color for messages QSLed on other bands
            if (holder.callListMessageTextView != null) {
                holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                        R.color.fromcall_is_qso_text_color));
            }
        } else {
            if (holder.callListMessageTextView != null) {
                holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                        R.color.message_text_color));
            }
        }

        // === [FIX] Null-safe setText ===
        if (holder.callListMessageTextView != null) {
            holder.callListMessageTextView.setText(holder.ft8Message.getMessageText(true));
        }
        // === END FIX ===

        //carrier frequency
        // === [FIX] Null-safe setText ===
        safeSetText(holder.bandItemTextView, BaseRigOperation.getFrequencyStr(holder.ft8Message.band));
        // === END FIX ===

        //calculate distance
        // === [FIX] Null-safe setText ===
        safeSetText(holder.callingListDistTextView, MaidenheadGrid.getDistStr(
                GeneralVariables.getMyMaidenheadGrid()
                , holder.ft8Message.getMaidenheadGrid(mainViewModel.databaseOpr)));
        // === END FIX ===

        // === NEW: Calculate and display azimuth with degree symbol ===
        if (holder.callingListAzimuthTextView != null) { // [FIX] Null check for simple mode
            String myGrid = GeneralVariables.getMyMaidenheadGrid();
            String targetGrid = holder.ft8Message.getMaidenheadGrid(mainViewModel.databaseOpr);
            if (myGrid != null && !myGrid.isEmpty() && targetGrid != null && !targetGrid.isEmpty()) {
                double azimuth = MaidenheadGrid.getAzimuth(myGrid, targetGrid);
                if (azimuth >= 0) {
                    // \u00B0 = Unicode degree symbol (°) - safe for UTF-8 compilation
                    holder.callingListAzimuthTextView.setText(String.format(Locale.US, "%.0f\u00B0", azimuth));
                } else {
                    holder.callingListAzimuthTextView.setText("--");
                }
            } else {
                holder.callingListAzimuthTextView.setText("--");
            }
        }
        // === END NEW ===

        // === [FIX] Null-safe setText ===
        safeSetText(holder.callingListCallsignToTextView, "");
        safeSetText(holder.callingListCallsignFromTextView, "");
        // === END FIX ===

        //message type
        // === [FIX] Null-safe setText ===
        safeSetText(holder.callingListCommandIInfoTextView, holder.ft8Message.getCommandInfo());
        // === END FIX ===

        if (holder.callingListCommandIInfoTextView != null) {
            if (holder.ft8Message.i3 == 1 || holder.ft8Message.i3 == 2) {
                holder.callingListCommandIInfoTextView.setTextColor(context.getResources().getColor(
                        R.color.text_view_color));
            } else {
                holder.callingListCommandIInfoTextView.setTextColor(context.getResources().getColor(
                        R.color.message_in_my_call_text_color));
            }
        }

        //set CQ color
        if (holder.ft8Message.checkIsCQ()) {
            if (holder.callListMessageTextView != null) {
                holder.callListMessageTextView.setBackgroundResource(R.color.textview_cq_color);
            }
            holder.ft8Message.toWhere = "";
        } else {
            if (holder.callListMessageTextView != null) {
                holder.callListMessageTextView.setBackgroundResource(R.color.textview_none_color);
            }
        }

        // === [FIX] Null-safe setText ===
        if (holder.callingListCallsignFromTextView != null) {
            if (holder.ft8Message.fromWhere != null) {
                holder.callingListCallsignFromTextView.setText(holder.ft8Message.fromWhere);
            } else {
                holder.callingListCallsignFromTextView.setText("");
            }
        }
        if (holder.callingListCallsignToTextView != null) {
            if (holder.ft8Message.toWhere != null) {
                holder.callingListCallsignToTextView.setText(holder.ft8Message.toWhere);
            } else {
                holder.callingListCallsignToTextView.setText("");
            }
        }
        // === END FIX ===

        //mark partitions not QSLed
        setToDxcc(holder);
        setFromDxcc(holder);

        if (holder.ft8Message.freq_hz <= 0.01f) {//this is transmit interface
            setViewVisibility(holder.callingListIdBTextView, View.GONE);
            setViewVisibility(holder.callListDtTextView, View.GONE);
            //holder.callingListFreqTextView.setText("TX");
            float audioFreq = GeneralVariables.getBaseFrequency();
            // === [FIX] Null-safe setText ===
            if (holder.callingListFreqTextView != null) {
                holder.callingListFreqTextView.setText(String.format(Locale.US, "TX  %04.0f", audioFreq));
            }
            // === END FIX ===
            setViewVisibility(holder.bandItemTextView, View.GONE);
            setViewVisibility(holder.callingListDistTextView, View.GONE);
            setViewVisibility(holder.callingListCommandIInfoTextView, View.GONE);
            setViewVisibility(holder.callingUtcTextView, View.GONE);
            setViewVisibility(holder.callingListCallsignToTextView, View.GONE);
            setViewVisibility(holder.callingListCallsignFromTextView, View.GONE);
            setViewVisibility(holder.dxccToImageView, View.GONE);
            setViewVisibility(holder.ituToImageView, View.GONE);
            setViewVisibility(holder.cqToImageView, View.GONE);
            setViewVisibility(holder.dxccFromImageView, View.GONE);
            setViewVisibility(holder.ituFromImageView, View.GONE);
            setViewVisibility(holder.cqFromImageView, View.GONE);
            // === NEW: Hide azimuth in TX mode ===
            setViewVisibility(holder.callingListAzimuthTextView, View.GONE);
            // === END NEW ===
        } else if (GeneralVariables.simpleCallItemMode){//simple list mode
            setViewVisibility(holder.bandItemTextView, View.GONE);
            setViewVisibility(holder.callingListDistTextView, View.GONE);
            setViewVisibility(holder.callingListCommandIInfoTextView, View.GONE);
            setViewVisibility(holder.callingUtcTextView, View.GONE);
            setViewVisibility(holder.callingListCallsignToTextView, View.GONE);
            setViewVisibility(holder.dxccToImageView, View.GONE);
            setViewVisibility(holder.ituToImageView, View.GONE);
            setViewVisibility(holder.cqToImageView, View.GONE);
            // === NEW: Hide azimuth in simple mode ===
            setViewVisibility(holder.callingListAzimuthTextView, View.GONE);
            // === END NEW ===
        }else {//standard list mode
            setViewVisibility(holder.callingListIdBTextView, View.VISIBLE);
            setViewVisibility(holder.callListDtTextView, View.VISIBLE);
            setViewVisibility(holder.bandItemTextView, View.VISIBLE);
            setViewVisibility(holder.callingListDistTextView, View.VISIBLE);
            setViewVisibility(holder.callingListCommandIInfoTextView, View.VISIBLE);
            setViewVisibility(holder.callingUtcTextView, View.VISIBLE);
            setViewVisibility(holder.callingListCallsignToTextView, View.VISIBLE);
            setViewVisibility(holder.callingListCallsignFromTextView, View.VISIBLE);
            // === NEW: Show azimuth in standard mode ===
            setViewVisibility(holder.callingListAzimuthTextView, View.VISIBLE);
            // === END NEW ===
        }
    }

    // === [NEW] Helper methods for null-safe operations ===

    /**
     * Safely set text on TextView, checking for null first
     */
    private void safeSetText(TextView textView, String text) {
        if (textView != null) {
            textView.setText(text);
        }
    }

    /**
     * Safely set view visibility, checking for null first
     */
    private void setViewVisibility(View view, int visibility) {
        if (view != null) {
            view.setVisibility(visibility);
        }
    }
    // === END NEW ===

    // [FIX-RECYCLER-ICONS 2026-05-17] BEGIN
    /**
     * Set visibility of DXCC/CQ/ITU icons for FROM callsign
     * CRITICAL: Always reset to GONE first to prevent RecyclerView recycling artifacts
     */
    private void setFromDxcc(@NonNull CallingListItemHolder holder) {
        // [FIX-RECYCLER-ICONS 2026-05-17] Safety check for null message
        if (holder.ft8Message == null) {
            setViewVisibility(holder.dxccFromImageView, View.GONE);
            setViewVisibility(holder.cqFromImageView, View.GONE);
            setViewVisibility(holder.ituFromImageView, View.GONE);
            return;
        }

        // [FIX-RECYCLER-ICONS 2026-05-17] STEP 1: Reset ALL icons to GONE first
        // This prevents "ghost icons" from previous recycled ViewHolders
        setViewVisibility(holder.dxccFromImageView, View.GONE);
        setViewVisibility(holder.cqFromImageView, View.GONE);
        setViewVisibility(holder.ituFromImageView, View.GONE);

        // [FIX-RECYCLER-ICONS 2026-05-17] STEP 2: Set VISIBLE only if conditions are met
        // Note: freq_hz check ensures we don't show icons in TX mode
        if (holder.ft8Message.fromDxcc && holder.ft8Message.freq_hz > 0.01f) {
            setViewVisibility(holder.dxccFromImageView, View.VISIBLE);
        }
        // else branch removed - already set to GONE above [FIX-RECYCLER-ICONS 2026-05-17]

        if (holder.ft8Message.fromCq && holder.ft8Message.freq_hz > 0.01f) {
            setViewVisibility(holder.cqFromImageView, View.VISIBLE);
        }
        // else branch removed - already set to GONE above [FIX-RECYCLER-ICONS 2026-05-17]

        if (holder.ft8Message.fromItu && holder.ft8Message.freq_hz > 0.01f) {
            setViewVisibility(holder.ituFromImageView, View.VISIBLE);
        }
        // else branch removed - already set to GONE above [FIX-RECYCLER-ICONS 2026-05-17]
    }
    // [FIX-RECYCLER-ICONS 2026-05-17] END

    // [FIX-RECYCLER-ICONS 2026-05-17] BEGIN
    /**
     * Set visibility of DXCC/CQ/ITU icons for TO callsign
     * CRITICAL: Always reset to GONE first to prevent RecyclerView recycling artifacts
     */
    private void setToDxcc(@NonNull CallingListItemHolder holder) {
        // [FIX-RECYCLER-ICONS 2026-05-17] Safety check for null message
        if (holder.ft8Message == null) {
            setViewVisibility(holder.dxccToImageView, View.GONE);
            setViewVisibility(holder.cqToImageView, View.GONE);
            setViewVisibility(holder.ituToImageView, View.GONE);
            return;
        }

        // [FIX-RECYCLER-ICONS 2026-05-17] STEP 1: Reset ALL icons to GONE first
        setViewVisibility(holder.dxccToImageView, View.GONE);
        setViewVisibility(holder.cqToImageView, View.GONE);
        setViewVisibility(holder.ituToImageView, View.GONE);

        // [FIX-RECYCLER-ICONS 2026-05-17] STEP 2: Set VISIBLE only if conditions are met
        if (holder.ft8Message.toDxcc && holder.ft8Message.freq_hz > 0.01f) {
            setViewVisibility(holder.dxccToImageView, View.VISIBLE);
        }

        if (holder.ft8Message.toCq && holder.ft8Message.freq_hz > 0.01f) {
            setViewVisibility(holder.cqToImageView, View.VISIBLE);
        }

        if (holder.ft8Message.toItu && holder.ft8Message.freq_hz > 0.01f) {
            setViewVisibility(holder.ituToImageView, View.VISIBLE);
        }
    }
    // [FIX-RECYCLER-ICONS 2026-05-17] END

    //check if callsign was QSLed
    private void setQueryHolderQSL_Callsign(@NonNull CallingListItemHolder holder) {
        //check if QSLed on this band
        if (GeneralVariables.checkQSLCallsign(holder.ft8Message.getCallsignFrom())) {//if in database, strike through
            if (holder.callListMessageTextView != null) {
                holder.callListMessageTextView.setPaintFlags(
                        holder.callListMessageTextView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
        } else {//if not in database, remove strike through
            if (holder.callListMessageTextView != null) {
                holder.callListMessageTextView.setPaintFlags(
                        holder.callListMessageTextView.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            }
        }
        holder.otherBandIsQso = GeneralVariables.checkQSLCallsign_OtherBand(holder.ft8Message.getCallsignFrom());
    }

    @Override
    public int getItemCount() {
        return ft8MessageArrayList.size();
    }

    public void setOnItemClickListener(View.OnClickListener onItemClickListener) {
        this.onItemClickListener = onItemClickListener;
    }

    static class CallingListItemHolder extends RecyclerView.ViewHolder {
        private static final String TAG = "CallingListItemHolder";
        ConstraintLayout callListHolderConstraintLayout;
        TextView callingListIdBTextView, callListDtTextView, callingListFreqTextView,
                callListMessageTextView, callingListDistTextView, callingListSequenceTextView,
                callingListCallsignFromTextView, callingListCallsignToTextView
                , callingListCommandIInfoTextView,
                bandItemTextView, callingUtcTextView,
                callingListAzimuthTextView; // === NEW: Azimuth TextView ===
        ImageView dxccToImageView, ituToImageView, cqToImageView, dxccFromImageView
                , ituFromImageView, cqFromImageView,isWeakSignalImageView;
        public Ft8Message ft8Message;
        //boolean showFollow;
        ShowMode showMode;
        boolean isSyncFreq;
        boolean otherBandIsQso = false;


        public CallingListItemHolder(@NonNull View itemView, View.OnClickListener listener
                ,View.OnCreateContextMenuListener menuListener) {
            super(itemView);
            callListHolderConstraintLayout = itemView.findViewById(R.id.callListHolderConstraintLayout);
            callingListIdBTextView = itemView.findViewById(R.id.callingListIdBTextView);
            callListDtTextView = itemView.findViewById(R.id.callListDtTextView);
            callingListFreqTextView = itemView.findViewById(R.id.callingListFreqTextView);
            callListMessageTextView = itemView.findViewById(R.id.callListMessageTextView);
            callingListDistTextView = itemView.findViewById(R.id.callingListDistTextView);
            callingListSequenceTextView = itemView.findViewById(R.id.callingListSequenceTextView);
            callingListCallsignFromTextView = itemView.findViewById(R.id.callingListCallsignFromTextView);
            callingListCallsignToTextView = itemView.findViewById(R.id.callToItemTextView);
            callingListCommandIInfoTextView = itemView.findViewById(R.id.callingListCommandIInfoTextView);
            bandItemTextView = itemView.findViewById(R.id.bandItemTextView);
            callingUtcTextView = itemView.findViewById(R.id.callingUtcTextView);
            // === NEW: Find azimuth TextView (may be null in simple mode) ===
            callingListAzimuthTextView = itemView.findViewById(R.id.callingListAzimuthTextView);
            // === END NEW ===

            dxccToImageView = itemView.findViewById(R.id.dxccToImageView);
            ituToImageView = itemView.findViewById(R.id.ituToImageView);
            cqToImageView = itemView.findViewById(R.id.cqToImageView);
            dxccFromImageView = itemView.findViewById(R.id.dxccFromImageView);
            ituFromImageView = itemView.findViewById(R.id.ituFromImageView);
            cqFromImageView = itemView.findViewById(R.id.cqFromImageView);
            isWeakSignalImageView=itemView.findViewById(R.id.isWeakSignalImageView);

            dxccToImageView.setVisibility(View.GONE);
            ituToImageView.setVisibility(View.GONE);
            cqToImageView.setVisibility(View.GONE);
            dxccFromImageView.setVisibility(View.GONE);
            ituFromImageView.setVisibility(View.GONE);
            cqFromImageView.setVisibility(View.GONE);
            itemView.setTag(-1);
            itemView.setOnClickListener(listener);
            itemView.setOnCreateContextMenuListener(menuListener);

        }
    }
}