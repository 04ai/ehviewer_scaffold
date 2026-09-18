package com.example.ehviewer_scaffold.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.rust.GalleryItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onGalleryClick: (gid: String, token: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var selectedFolder by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun loadFavorites(folder: Int) {
        scope.launch {
            isLoading = true
            try {
                val page = EhRustBridge.getCustomList(path = "favorites.php?favcat=$folder", page = 0)
                items = page.items
            } catch (_: Exception) {
                items = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(selectedFolder) {
        loadFavorites(selectedFolder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏夹", fontWeight = FontWeight.Bold) }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(10) { index ->
                    FilterChip(
                        selected = selectedFolder == index,
                        onClick = { selectedFolder = index },
                        label = { Text("收藏夹 $index") }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    items.isEmpty() -> {
                        Text("当前收藏夹为空或需要先登录", modifier = Modifier.align(Alignment.Center))
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items, key = { it.gid }) { item ->
                                GalleryItemCard(
                                    item = item,
                                    onClick = { onGalleryClick(item.gid, item.token) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
