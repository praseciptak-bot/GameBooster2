package com.example.gamebooster

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GameAdapter(
    private val games: MutableList<GameApp>,
    private val onLaunch: (GameApp) -> Unit
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {

    class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val name: TextView = view.findViewById(R.id.tvGameName)
        val launchBtn: Button = view.findViewById(R.id.btnLaunch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return GameViewHolder(view)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = games[position]
        holder.name.text = game.label
        holder.icon.setImageDrawable(game.icon)
        holder.launchBtn.setOnClickListener { onLaunch(game) }
    }

    override fun getItemCount(): Int = games.size

    fun addGame(game: GameApp) {
        if (games.none { it.packageName == game.packageName }) {
            games.add(game)
            notifyItemInserted(games.size - 1)
        }
    }

    fun setGames(newGames: List<GameApp>) {
        games.clear()
        games.addAll(newGames)
        notifyDataSetChanged()
    }
}
