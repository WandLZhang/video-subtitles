package com.k2fsa.sherpa.onnx

import android.graphics.Color
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Japanese morphological analysis via kuromoji (on-device). Segments a sentence, merges each
 * conjugated word (verb/adjective + its folded-in auxiliaries) into one unit, tags it by part of
 * speech, and derives an English "form label" from the auxiliaries. Dictionary lookup uses the
 * base (dictionary) form — POS tags/base forms verified against kuromoji-ipadic output.
 */
object JaAnalyzer {
    data class Seg(val text: String, val color: Int, val base: String, val pos: String, val form: String)

    private val tokenizer by lazy { Tokenizer() }

    private val VERB = Color.parseColor("#FF7043")
    private val NOUN = Color.parseColor("#5A8FE1")
    private val ADJ = Color.parseColor("#3FAE4F")
    private val ADV = Color.parseColor("#B06FE0")
    private val CONJ = Color.parseColor("#2CC0B4")
    private val INTJ = Color.parseColor("#E85AA0")
    private val PART = Color.parseColor("#9AA0A6")
    private val MUTE = Color.parseColor("#8A8F98")
    private val DEF = Color.parseColor("#DCEBF5")

    fun warmup() { try { tokenizer.tokenize("あ") } catch (e: Exception) {} }

    private fun base(t: Token): String {
        val b = t.baseForm
        return if (b.isNullOrEmpty() || b == "*") t.surface else b
    }

    /** Tokens that fold into the preceding verb/adjective (auxiliaries, te-form, helper verbs, suffixes). */
    private fun isInflection(n: Token): Boolean {
        val p1 = n.partOfSpeechLevel1
        val p2 = n.partOfSpeechLevel2
        return p1 == "助動詞" ||
            (p1 == "動詞" && (p2 == "非自立" || p2 == "接尾")) ||
            (p1 == "助詞" && p2 == "接続助詞" && n.surface in setOf("て", "で", "ば", "たり", "ちゃ", "じゃ")) ||
            (p1 == "名詞" && p2 == "接尾") ||
            (p1 == "形容詞" && p2 == "接尾")
    }

    fun analyze(text: String): List<Seg> {
        val toks: List<Token> = try { tokenizer.tokenize(text) } catch (e: Exception) {
            return listOf(Seg(text, DEF, text, "", ""))
        }
        val segs = ArrayList<Seg>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val p1 = t.partOfSpeechLevel1
            val p2 = t.partOfSpeechLevel2

            // prefix folds into the following word
            if (p1 == "接頭詞" && i + 1 < toks.size) {
                val n = toks[i + 1]
                segs.add(makeSeg(t.surface + n.surface, n))
                i += 2
                continue
            }

            // サ変 noun + する → one verb (勉強する / 勉強しています)
            if (p1 == "名詞" && (p2 == "サ変接続" || p2 == "サ変・スル") &&
                i + 1 < toks.size && toks[i + 1].partOfSpeechLevel1 == "動詞" && base(toks[i + 1]) == "する") {
                val head = t
                val sb = StringBuilder(t.surface)
                val aux = ArrayList<String>()
                i++                         // move onto する
                sb.append(toks[i].surface)
                i++
                while (i < toks.size && isInflection(toks[i])) {
                    sb.append(toks[i].surface); aux.add(toks[i].surface); aux.add(base(toks[i])); i++
                }
                segs.add(Seg(sb.toString(), VERB, base(head), "verb", formLabel(aux)))
                continue
            }

            val isVerb = p1 == "動詞"
            val isIAdj = p1 == "形容詞"
            val isNaAdj = p1 == "名詞" && p2 == "形容動詞語幹"
            if (isVerb || isIAdj || isNaAdj) {
                val head = t
                val sb = StringBuilder(t.surface)
                val aux = ArrayList<String>()
                i++
                while (i < toks.size && isInflection(toks[i])) {
                    sb.append(toks[i].surface); aux.add(toks[i].surface); aux.add(base(toks[i])); i++
                }
                val color = if (isVerb) VERB else ADJ
                val pos = if (isVerb) "verb" else "adjective"
                segs.add(Seg(sb.toString(), color, base(head), pos, formLabel(aux)))
                continue
            }

            segs.add(makeSeg(t.surface, t))
            i++
        }
        return segs
    }

    private fun makeSeg(text: String, t: Token): Seg {
        val p1 = t.partOfSpeechLevel1
        val p2 = t.partOfSpeechLevel2
        val (color, pos) = when {
            p1 == "動詞" -> VERB to "verb"
            p1 == "形容詞" -> ADJ to "adjective"
            p1 == "名詞" && p2 == "形容動詞語幹" -> ADJ to "adjective"
            p1 == "名詞" -> NOUN to "noun"
            p1 == "副詞" -> ADV to "adverb"
            p1 == "接続詞" -> CONJ to "conjunction"
            p1 == "感動詞" -> INTJ to "interjection"
            p1 == "助詞" -> PART to "particle"
            p1 == "助動詞" -> PART to "auxiliary"   // stray copula/aux → muted, not a fake verb
            p1 == "連体詞" -> PART to "adnominal"
            p1 == "フィラー" -> MUTE to "filler"
            p1 == "記号" -> DEF to ""
            else -> DEF to (p1 ?: "")
        }
        return Seg(text, color, base(t), pos, "")
    }

    private fun formLabel(aux: List<String>): String {
        if (aux.isEmpty()) return ""
        val s = aux.toSet()
        fun has(vararg xs: String) = xs.any { it in s }
        val out = ArrayList<String>()
        if (has("ます", "です")) out.add("polite")
        if (has("れる", "られる")) out.add("passive/potential")
        if (has("せる", "させる")) out.add("causative")
        if (has("たい", "たがる")) out.add("want-to")
        if (has("いる", "おる", "てる", "でる")) out.add("progressive")
        else if (has("て", "で")) out.add("te-form")
        if (has("ない", "ん", "ぬ", "ず")) out.add("negative")
        if (has("た", "だ")) out.add("past")
        if (has("う", "よう")) out.add("volitional")
        if (has("ば", "たら")) out.add("conditional")
        return out.joinToString(" · ")
    }
}
