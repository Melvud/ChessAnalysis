package com.example.chessanalysis.data.repo

import android.util.Log
import com.example.chessanalysis.data.local.entity.GameStatsEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

internal class FirestoreSync(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    companion object {
        private const val TAG = "FirestoreSync"
        private const val COLLECTION = "stats"
    }

    init {
        // Включаем офлайн-персистентность Firestore (по умолчанию включена, но явно не повредит).
        // Оффлайн-режим Firestore сам кэширует и синкает данные при появлении сети. :contentReference[oaicite:2]{index=2}
        try {
            // С 2020-х оффлайн включён по дефолту в Android, поэтому без явной настройки.
            // Оставим комментарий для понимания.
        } catch (_: Throwable) {}
    }

    suspend fun ensureUser() : String {
        val current = auth.currentUser
        if (current != null) return current.uid
        val res = auth.signInAnonymously().await()
        return res.user?.uid ?: "anonymous"
    }

    suspend fun push(uid: String, item: GameStatsEntity): Long {
        val map = mapOf(
            "id" to item.id,
            "createdAt" to item.createdAt,
            "accuracy" to item.accuracy,
            "performance" to item.performance,
            "opponentRating" to item.opponentRating,
            "moveCount" to item.moveCount,
            "bestCount" to item.bestCount,
            "brilliantCount" to item.brilliantCount,
            "greatCount" to item.greatCount,
            "goodCount" to item.goodCount,
            "inaccuracyCount" to item.inaccuracyCount,
            "mistakeCount" to item.mistakeCount,
            "blunderCount" to item.blunderCount,
            "updatedAt" to item.updatedAt
        )
        db.collection("users").document(uid)
            .collection(COLLECTION).document(item.id)
            .set(map, SetOptions.merge())
            .await()
        val ts = System.currentTimeMillis()
        Log.d(TAG, "Pushed stat ${item.id} to Firestore at $ts")
        return ts
    }
}
