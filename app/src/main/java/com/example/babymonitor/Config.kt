package com.example.babymonitor

import android.content.Context

/** Configuração persistida no aparelho (SharedPreferences privadas do app). */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun suffix(c: Context): String = p(c).getString("suffix", "BabaEletronica") ?: "BabaEletronica"
    fun pass(c: Context): String = p(c).getString("pass", "") ?: ""
    fun ssid(c: Context): String = "DIRECT-BB-" + suffix(c)        // SSID de Wi-Fi Direct: "DIRECT-xx-..." (máx. 32)

    /** 0 = desligado, 1 = sensibilidade alta, 2 = média, 3 = baixa */
    fun voxMode(c: Context): Int = p(c).getInt("vox", 0)
    fun setVox(c: Context, m: Int) = p(c).edit().putInt("vox", m).apply()

    fun save(c: Context, suffix: String, pass: String) =
        p(c).edit().putString("suffix", suffix).putString("pass", pass).apply()

    /** Retorna a mensagem de erro, ou null se estiver válido. */
    fun validate(suffix: String, pass: String): String? = when {
        !suffix.matches(Regex("[A-Za-z0-9]{1,20}")) -> "Nome da rede: use de 1 a 20 letras ou números, sem espaços."
        pass.length !in 8..63 || pass.any { it.code !in 32..126 } ->
            "Senha: 8 a 63 caracteres (letras, números e símbolos comuns, sem acentos)."
        else -> null
    }
}
