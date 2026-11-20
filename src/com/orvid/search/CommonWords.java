package com.orvid.search;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class CommonWords {

    private static final Set<String> WORDS = new HashSet<>(Arrays.asList((
            "about after all amateur anal and are asian ass babe back bbc beautiful beauty before best big bigwhore black "
            + "blonde blowjob boobs brunette busty can chick cock com creampie cum cumshot cute day deep der dick during "
            + "ebony first for free from fuck fucked fucking fucks full get gets getting girl girls gives guy hard hardcore "
            + "has having hentai her here him his horny hot how huge into japanese just kids latina lesbian life like live "
            + "love loves make man mature milf mom more mouth naked natural naughty never new off old one our out over part "
            + "petite play playing porn pov pussy real redhead rides rough russian scene sex sexy she slut steamy stepmom "
            + "takes teen that the their they this threesome tight time tits top trailer two video was watch wet what when "
            + "while white whores why wife wild with woman world xxx you young your").split(" ")));

    private CommonWords() {
    }

    static boolean has(String word) {
        return WORDS.contains(word);
    }
}
