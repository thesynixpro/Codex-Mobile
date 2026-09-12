package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_conversations")
data class ChatConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val projectUri: String? = null,
    val projectName: String? = null,
    val model: String = "gpt-4o"
)
