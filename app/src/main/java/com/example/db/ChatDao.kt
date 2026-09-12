package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    suspend fun getAllConversations(): List<ChatConversationEntity>

    @Query("SELECT * FROM chat_conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ChatConversationEntity?

    @Query("""
        SELECT DISTINCT c.* FROM chat_conversations c
        LEFT JOIN chat_messages m ON c.id = m.conversationId
        WHERE c.title LIKE '%' || :query || '%'
           OR c.projectName LIKE '%' || :query || '%'
           OR m.content LIKE '%' || :query || '%'
        ORDER BY c.updatedAt DESC
    """)
    suspend fun searchConversations(query: String): List<ChatConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ChatConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ChatConversationEntity)

    @Query("UPDATE chat_conversations SET title = :newTitle, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationTitle(id: String, newTitle: String, updatedAt: Long)

    @Query("UPDATE chat_conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Long)

    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversation(conversationId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("UPDATE chat_messages SET appliedStatus = :applied WHERE id = :messageId")
    suspend fun updateMessageAppliedStatus(messageId: String, applied: Boolean)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}
