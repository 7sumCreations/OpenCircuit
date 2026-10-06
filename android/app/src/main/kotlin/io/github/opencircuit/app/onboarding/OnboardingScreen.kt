package io.github.opencircuit.app.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * First-run onboarding: four swipeable pages ([OnboardingPages]) with Continue, Skip on the first
 * three and Get started on the last (`ios/OpenCircuit/OnboardingView.swift:23-56` @ b1c2fdd).
 * Skip and Get started both call [onDone]. Pure presentation: it asks for no permission.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val pages = OnboardingPages.all
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                Page(pages[index])
            }
            val current = pager.currentPage
            Text(
                text = (0 until pages.size).joinToString(" ") { if (it == current) "●" else "○" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = "Page ${current + 1} of ${pages.size}" },
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (OnboardingPages.showsSkip(current)) TextButton(onClick = onDone) { Text("Skip") }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (current < pages.lastIndex) scope.launch { pager.animateScrollToPage(current + 1) } else onDone()
                    },
                ) { Text(OnboardingPages.primaryLabel(current)) }
            }
        }
    }
}

@Composable
private fun Page(page: OnboardingPage) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(page.title, style = MaterialTheme.typography.headlineSmall)
        page.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}
