package com.dating.safety.domain;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gesture the user must show on the verification selfie. Random per request, so an old or stolen photo
 * cannot pass.
 */
public enum Gesture {
    THUMB_UP("Покажите большой палец вверх"),
    TWO_FINGERS("Покажите два пальца (знак «победа»)"),
    THREE_FINGERS("Покажите три пальца"),
    OPEN_PALM("Покажите открытую ладонь рядом с лицом"),
    HAND_ON_HEAD("Положите ладонь на макушку"),
    TOUCH_EAR("Коснитесь мочки уха");

    private final String instruction;

    Gesture(String instruction) {
        this.instruction = instruction;
    }

    public String instruction() {
        return instruction;
    }

    public static Gesture random() {
        Gesture[] all = values();
        return all[ThreadLocalRandom.current().nextInt(all.length)];
    }
}
