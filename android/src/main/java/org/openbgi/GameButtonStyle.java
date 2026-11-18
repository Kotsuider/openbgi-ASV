/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.widget.Button;

final class GameButtonStyle {
    static Button create(Context context, String label, boolean outlined, Runnable onClick) {
        Button button = new Button(context);
        button.setText(label);
        button.setContentDescription(label);
        button.setAllCaps(false);
        button.setSingleLine();
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        float density = context.getResources().getDisplayMetrics().density;
        int padding = Math.round(6 * density);
        button.setPadding(padding, 0, padding, 0);
        button.setTextColor(outlined ? 0xffe6e1e9 : 0xff381e72);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(100 * density);
        background.setColor(outlined ? 0x001c1b1f : 0xffd0bcff);
        if (outlined) background.setStroke(Math.round(density), 0xff938f99);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(100 * density);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44808080), background, mask));
        button.setOnClickListener(view -> onClick.run());
        return button;
    }
}
