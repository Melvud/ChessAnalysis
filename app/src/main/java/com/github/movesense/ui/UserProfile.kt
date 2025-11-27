package com.github.movesense.ui

import android.annotation.SuppressLint
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Профиль пользователя. Parcelable — чтобы его можно было класть в Bundle,
 * а значит и хранить в rememberSaveable без кастомных Saver-ов.
 */
@SuppressLint("ParcelCreator")
@Parcelize
data class UserProfile(
    val email: String = "",
    val nickname: String = "",
    val lichessUsername: String = "",
    val chessUsername: String = "",
    val language: String = "ru",
    val isPremium: Boolean = false,
    val isAdmin: Boolean = false
) : Parcelable
