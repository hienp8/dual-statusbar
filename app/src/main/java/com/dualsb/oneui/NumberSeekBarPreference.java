package com.dualsb.oneui;

import android.content.Context;
import android.graphics.Paint;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

/** Thanh trượt như bình thường, nhưng bấm vào con số bên phải để gõ trực tiếp giá trị. */
public class NumberSeekBarPreference extends SeekBarPreference {

    public NumberSeekBarPreference(Context c, AttributeSet a, int defStyleAttr, int defStyleRes) {
        super(c, a, defStyleAttr, defStyleRes);
    }
    public NumberSeekBarPreference(Context c, AttributeSet a, int defStyleAttr) { super(c, a, defStyleAttr); }
    public NumberSeekBarPreference(Context c, AttributeSet a) { super(c, a); }
    public NumberSeekBarPreference(Context c) { super(c); }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View v = holder.findViewById(androidx.preference.R.id.seekbar_value);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setPaintFlags(tv.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);   // gợi ý là bấm được
            int pad = dp(8);
            tv.setPadding(pad, pad, pad, pad);                                  // vùng chạm rộng hơn
            tv.setClickable(true);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showDialog(); }
            });
        }
    }

    private int dp(int v) {
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }

    private void showDialog() {
        final Context c = getContext();
        final int min = getMin(), max = getMax();
        final EditText et = new EditText(c);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | (min < 0 ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        et.setText(String.valueOf(getValue()));
        et.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(c);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et);

        AlertDialog dlg = new AlertDialog.Builder(c)
                .setTitle(getTitle())
                .setMessage("Nhập số từ " + min + " đến " + max)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int val = Integer.parseInt(et.getText().toString().trim());
                            setValue(Math.max(min, Math.min(max, val)));      // vượt khoảng thì tự đưa về mốc gần nhất
                        } catch (NumberFormatException e) {
                            Toast.makeText(c, "Số không hợp lệ", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }
}
