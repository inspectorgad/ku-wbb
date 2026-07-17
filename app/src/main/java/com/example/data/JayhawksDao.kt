package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JayhawksDao {

    // Players
    @Query("SELECT * FROM players ORDER BY name COLLATE NOCASE")
    fun observePlayers(): Flow<List<Player>>

    @Query("SELECT * FROM players")
    suspend fun playersOnce(): List<Player>

    @Query("SELECT * FROM games")
    suspend fun gamesOnce(): List<Game>

    @Query("SELECT * FROM stat_lines")
    suspend fun statLinesOnce(): List<StatLine>

    @Insert
    suspend fun insertPlayer(player: Player): Long

    @Update
    suspend fun updatePlayer(player: Player)

    @Delete
    suspend fun deletePlayer(player: Player)

    // Games
    @Query("SELECT * FROM games ORDER BY date DESC, id DESC")
    fun observeGames(): Flow<List<Game>>

    @Insert
    suspend fun insertGame(game: Game): Long

    @Update
    suspend fun updateGame(game: Game)

    @Delete
    suspend fun deleteGame(game: Game)

    // Stat lines
    @Query("SELECT * FROM stat_lines")
    fun observeStatLines(): Flow<List<StatLine>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatLine(line: StatLine): Long

    @Delete
    suspend fun deleteStatLine(line: StatLine)
}
