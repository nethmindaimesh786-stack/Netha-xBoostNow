package com.netha.keyboardultra

import org.junit.Assert.assertEquals
import org.junit.Test

class SinhalaTransliteratorTest {
    @Test fun convertsTheRequestedSpellings() {
        assertEquals("අයිය", SinhalaTransliterator.commit("ayiya"))
        assertEquals("තාත්තා", SinhalaTransliterator.commit("thaaththas"))
        assertEquals("ඇ", SinhalaTransliterator.commit("A"))
        assertEquals("කැරියා", SinhalaTransliterator.commit("kariya"))
        assertEquals("ප්‍රශ්න", SinhalaTransliterator.commit("prashna"))
        assertEquals("ප්‍රශ්නය", SinhalaTransliterator.commit("prashnaya"))
    }

    @Test fun convertsRequestedSingleLettersAndVowels() {
        assertEquals("ප්", SinhalaTransliterator.commit("p"))
        assertEquals("ක්", SinhalaTransliterator.commit("k"))
        assertEquals("ක්", SinhalaTransliterator.commit("c"))
        assertEquals("ච", SinhalaTransliterator.commit("cha"))
        assertEquals("ද්", SinhalaTransliterator.commit("q"))
        assertEquals("ඩ්", SinhalaTransliterator.commit("d"))
        assertEquals("දාස්", SinhalaTransliterator.commit("qaas"))
        assertEquals("ඩා", SinhalaTransliterator.commit("daa"))
    }

    @Test fun shiftSelectsAdditionalSinhalaLetters() {
        assertEquals("ඛා", SinhalaTransliterator.commit("Kaa"))
        assertEquals("ඝා", SinhalaTransliterator.commit("Gaa"))
        assertEquals("ඡා", SinhalaTransliterator.commit("Caa"))
        assertEquals("ඣා", SinhalaTransliterator.commit("Jaa"))
        assertEquals("ඨා", SinhalaTransliterator.commit("Taa"))
        assertEquals("ඪා", SinhalaTransliterator.commit("Daa"))
        assertEquals("ණා", SinhalaTransliterator.commit("Naa"))
        assertEquals("ඵා", SinhalaTransliterator.commit("Paa"))
        assertEquals("භා", SinhalaTransliterator.commit("Baa"))
        assertEquals("ධා", SinhalaTransliterator.commit("Qaa"))
        assertEquals("ෂා", SinhalaTransliterator.commit("Saa"))
        assertEquals("ළා", SinhalaTransliterator.commit("Laa"))
        assertEquals("ඹා", SinhalaTransliterator.commit("Maa"))
        assertEquals("ඥා", SinhalaTransliterator.commit("Yaa"))
        assertEquals("ඦා", SinhalaTransliterator.commit("Zaa"))
        assertEquals("ඒ", SinhalaTransliterator.commit("E"))
    }

    @Test fun convertsSimplePhoneticSyllables() {
        assertEquals("ක", SinhalaTransliterator.commit("ka"))
        assertEquals("කා", SinhalaTransliterator.commit("kaa"))
        assertEquals("නම", SinhalaTransliterator.commit("nama"))
    }
}
