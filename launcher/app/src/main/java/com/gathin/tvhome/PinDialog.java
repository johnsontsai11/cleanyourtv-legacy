package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The parental PIN card: a lock, a title, a line for hints and errors, one dot per digit, and a round number pad
 * for the D-pad. The remote's number keys type too. The PIN is checked as soon as the last digit is in; a wrong
 * one shakes the dots red and clears them, without leaving the card.
 *
 * No on-screen keyboard is involved, because Sony's keeps a history of what was typed (even in password fields it
 * showed earlier PINs) that anyone could pick from.
 */
final class PinDialog {
    private static final int CARD = 0xFF1B212A, KEY = 0xFF2A323D, KEY_FOCUSED = 0xFFFFFFFF, INK = 0xFF101419,
            DIM = 0xB3FFFFFF, ERROR = 0xFFFF6B6B;
    private static final String BACKSPACE = "⌫", CLOSE = "✕";
    /** Wide enough for the title and hint; the pad is three 60 dp keys with 10 dp margins in the middle. */
    private static final int CARD_WIDTH_DP = 440;
    /** Keys small enough that the whole card (four rows) fits a 540 dp tall TV screen with room to spare. */
    private static final int KEY_DP = 60;

    /** What the card does with a full PIN: return null to close it, or an error to show and clear for a retry. */
    private interface Entry {
        CharSequence entered(String pin, Card card);
    }

    private PinDialog() {}

    /** Asks for the parental PIN; `ok` runs after the right one, `cancel` (may be null) on Back or ✕. */
    static void ask(Activity a, int title, Runnable ok, Runnable cancel) {
        open(a, a.getString(title), Parental.pinLength(a), (pin, card) -> {
            if (Parental.checkPin(a, pin)) {
                card.close();
                ok.run();
                return null;
            }
            return a.getString(Parental.coolingDown() ? R.string.parental_wait : R.string.parental_wrong_pin);
        }, cancel);
    }

    /** Asks for a child's profile password (the parental PIN works too); `ok` runs after the right one. */
    static void askKid(Activity a, int kid, Runnable ok, Runnable cancel) {
        open(a, a.getString(R.string.parental_enter_kid_password, Parental.kidName(Parental.prefs(a), kid)),
                Parental.kidPasswordLength(Parental.prefs(a), kid), (pin, card) -> {
            if (Parental.checkKidPassword(a, kid, pin)) {
                card.close();
                ok.run();
                return null;
            }
            return a.getString(Parental.coolingDown() ? R.string.parental_wait : R.string.parental_wrong_pin);
        }, cancel);
    }

    /** Asks for a new 4-digit password for a child's profile twice and saves it; `done` runs once saved. */
    static void createKid(Activity a, int kid, Runnable done) {
        String[] first = new String[1];
        open(a, a.getString(R.string.parental_new_kid_password), Parental.PIN_DIGITS, (pin, card) -> {
            if (first[0] == null) {
                first[0] = pin;
                card.retitle(a.getString(R.string.parental_confirm_pin));
                return "";
            }
            if (!first[0].equals(pin)) {
                first[0] = null;
                card.retitle(a.getString(R.string.parental_new_kid_password));
                return a.getString(R.string.parental_pin_mismatch);
            }
            Parental.setKidPassword(a, kid, pin);
            card.close();
            android.widget.Toast.makeText(a, R.string.parental_kid_password_saved,
                    android.widget.Toast.LENGTH_SHORT).show();
            done.run();
            return null;
        }, null);
    }

    /** Asks for a new 4-digit PIN twice, in the same card, and saves it; `done` runs once it is saved. */
    static void create(Activity a, Runnable done) {
        String[] first = new String[1];
        open(a, a.getString(R.string.parental_new_pin), Parental.PIN_DIGITS, (pin, card) -> {
            if (first[0] == null) {
                first[0] = pin;
                card.retitle(a.getString(R.string.parental_confirm_pin));
                return "";
            }
            if (!first[0].equals(pin)) {
                first[0] = null;
                card.retitle(a.getString(R.string.parental_new_pin));
                return a.getString(R.string.parental_pin_mismatch);
            }
            Parental.setPin(a, pin);
            card.close();
            android.widget.Toast.makeText(a, R.string.parental_pin_saved, android.widget.Toast.LENGTH_SHORT).show();
            done.run();
            return null;
        }, null);
    }

    private static void open(Activity a, String title, int digits, Entry entry, Runnable cancel) {
        if (a.isFinishing()) return;
        new Card(a, title, digits, entry, cancel).show();
    }

    /** One card on screen. */
    private static final class Card {
        private final Activity a;
        private final float dp;
        private final Dialog dialog;
        private final TextView titleView, hint;
        private final LinearLayout dotRow;
        private final View[] dots;
        private final StringBuilder pin = new StringBuilder();
        private final int digits;
        private final Entry entry;
        private boolean closed;

        Card(Activity a, String title, int digits, Entry entry, Runnable cancel) {
            this.a = a;
            this.digits = digits;
            this.entry = entry;
            dp = a.getResources().getDisplayMetrics().density;

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setBackground(round(CARD, 28));
            card.setPadding(px(40), px(28), px(40), px(24));

            ImageView lock = new ImageView(a);
            lock.setImageResource(R.drawable.ic_pin_lock);
            lock.setImageTintList(ColorStateList.valueOf(DIM));
            card.addView(lock, new LinearLayout.LayoutParams(px(32), px(32)));

            titleView = text(title, 26, Color.WHITE);
            titleView.setPadding(0, px(10), 0, 0);
            card.addView(titleView);
            hint = text(a.getString(R.string.parental_pin_hint), 15, DIM);
            hint.setPadding(0, px(4), 0, 0);
            card.addView(hint);

            dotRow = new LinearLayout(a);
            dotRow.setGravity(Gravity.CENTER);
            dotRow.setPadding(0, px(18), 0, px(18));
            dots = new View[digits];
            for (int i = 0; i < digits; i++) {
                dots[i] = new View(a);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(18), px(18));
                lp.setMargins(px(10), 0, px(10), 0);
                dotRow.addView(dots[i], lp);
            }
            card.addView(dotRow);

            GridLayout pad = new GridLayout(a);
            pad.setColumnCount(3);
            String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", BACKSPACE, "0", CLOSE};
            View first = null;
            for (String k : keys) {
                TextView b = key(k);
                b.setOnClickListener(v -> {
                    if (k.equals(BACKSPACE)) backspace();
                    else if (k.equals(CLOSE)) cancel();
                    else type(k.charAt(0));
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = px(KEY_DP);
                lp.height = px(KEY_DP);
                lp.setMargins(px(10), px(6), px(10), px(6));
                pad.addView(b, lp);
                if (first == null) first = b;
            }
            // Wrap, not the card's width, so the pad sits in the middle under the dots.
            card.addView(pad, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(card, new android.view.ViewGroup.LayoutParams(px(CARD_WIDTH_DP),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setOnCancelListener(d -> {
                closed = true;
                if (cancel != null) cancel.run();
            });
            // The remote's number keys type wherever the focus is; Del or Clear takes the last digit back.
            dialog.setOnKeyListener((d, keyCode, event) -> {
                int digit = keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9
                        ? keyCode - KeyEvent.KEYCODE_0
                        : keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9
                        ? keyCode - KeyEvent.KEYCODE_NUMPAD_0 : -1;
                boolean del = keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_CLEAR;
                if (digit < 0 && !del) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (del) backspace();
                    else type((char) ('0' + digit));
                }
                return true;
            });
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0.75f);
                // A plain Dialog's window wraps to a narrow default; the card needs its own width.
                w.setLayout(px(CARD_WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT);
            }
            showDots(false);
            if (first != null) first.requestFocus();
        }

        void show() {
            dialog.show();
        }

        void close() {
            closed = true;
            dialog.dismiss();
        }

        void cancel() {
            dialog.cancel();
        }

        void retitle(String t) {
            titleView.setText(t);
        }

        private void type(char c) {
            if (closed || pin.length() >= digits) return;
            pin.append(c);
            hint.setTextColor(DIM);
            showDots(false);
            if (pin.length() < digits) return;
            // Let the last dot fill before the card reacts.
            dotRow.postDelayed(() -> {
                if (closed) return;
                String entered = pin.toString();
                CharSequence error = entry.entered(entered, this);
                if (error == null) return;
                pin.setLength(0);
                if (error.length() == 0) {
                    // A step forward (the PIN again): fresh dots, the hint back.
                    hint.setText(R.string.parental_pin_hint);
                    showDots(false);
                } else {
                    hint.setText(error);
                    hint.setTextColor(ERROR);
                    showDots(true);
                    shake();
                }
            }, 150);
        }

        private void backspace() {
            if (closed || pin.length() == 0) return;
            pin.setLength(pin.length() - 1);
            showDots(false);
        }

        /** Filled for each digit typed, a ring for the rest; all red after a wrong PIN until the next digit. */
        private void showDots(boolean error) {
            for (int i = 0; i < dots.length; i++) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                if (error) {
                    g.setColor(ERROR);
                } else if (i < pin.length()) {
                    g.setColor(Color.WHITE);
                } else {
                    g.setColor(Color.TRANSPARENT);
                    g.setStroke(px(2), DIM);
                }
                dots[i].setBackground(g);
            }
        }

        private void shake() {
            ObjectAnimator.ofFloat(dotRow, View.TRANSLATION_X, 0, -px(14), px(14), -px(10), px(10), -px(5), 0)
                    .setDuration(380).start();
        }

        private TextView key(String k) {
            TextView b = new TextView(a);
            b.setText(k);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(k.length() == 1 && Character.isDigit(k.charAt(0)) ? 24 : 20);
            b.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_focused}, {}}, new int[]{INK, Color.WHITE}));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, oval(KEY_FOCUSED));
            bg.addState(new int[]{}, oval(KEY));
            b.setBackground(bg);
            b.setFocusable(true);
            b.setClickable(true);
            return b;
        }

        private TextView text(String s, int sp, int color) {
            TextView t = new TextView(a);
            t.setText(s);
            t.setTextSize(sp);
            t.setTextColor(color);
            t.setGravity(Gravity.CENTER);
            return t;
        }

        private GradientDrawable round(int color, int radiusDp) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(px(radiusDp));
            return g;
        }

        private static GradientDrawable oval(int color) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            return g;
        }

        private int px(int v) {
            return Math.round(v * dp);
        }
    }
}
