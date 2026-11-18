package ir.mariaplayer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.mariaplayer.R
import ir.mariaplayer.data.CollectionRule
import ir.mariaplayer.data.ContentType
import ir.mariaplayer.data.Track

/**
 * Builder for a CollectionRule — supports AND/OR composition of atomic
 * conditions (Duration, Regex, Types). Preview shows live match count.
 *
 * Used for both "New collection" and "Edit collection" flows.
 */
@Composable
fun CollectionRuleEditor(
    title: String,
    initialLabel: String,
    initialRule: CollectionRule,
    initialExclude: Boolean,
    allTracks: List<Track>?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSave: (label: String, rule: CollectionRule, exclude: Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var exclude by remember { mutableStateOf(initialExclude) }
    var combinator by remember {
        mutableStateOf(if (initialRule is CollectionRule.Or) "OR" else "AND")
    }
    var conditions by remember {
        mutableStateOf(
            when (initialRule) {
                is CollectionRule.And -> initialRule.rules
                is CollectionRule.Or -> initialRule.rules
                is CollectionRule.All -> emptyList()
                else -> listOf(initialRule)
            }
        )
    }

    val currentRule: CollectionRule = when {
        conditions.isEmpty() -> CollectionRule.Manual(emptyList())
        conditions.size == 1 -> conditions[0]
        combinator == "AND" -> CollectionRule.And(conditions)
        else -> CollectionRule.Or(conditions)
    }

    val previewCount = remember(currentRule, allTracks) {
        allTracks?.count { currentRule.matches(it) } ?: 0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = exclude, onCheckedChange = { exclude = it })
                    Text(
                        "Hide these tracks from other collections",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (conditions.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Match: ", style = MaterialTheme.typography.labelMedium)
                        FilterChip(
                            selected = combinator == "AND",
                            onClick = { combinator = "AND" },
                            label = { Text("AND") },
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = combinator == "OR",
                            onClick = { combinator = "OR" },
                            label = { Text("OR") },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                conditions.forEachIndexed { idx, cond ->
                    ConditionRow(
                        rule = cond,
                        onChange = { newRule ->
                            conditions = conditions.toMutableList().also { it[idx] = newRule }
                        },
                        onRemove = {
                            conditions = conditions.toMutableList().also { it.removeAt(idx) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                AddConditionButton { newCond -> conditions = conditions + newCond }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Preview: $previewCount tracks match",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank(),
                onClick = { onSave(label.trim(), currentRule, exclude) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ConditionRow(
    rule: CollectionRule,
    onChange: (CollectionRule) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val typeLabel = when (rule) {
                is CollectionRule.Duration -> "Duration"
                is CollectionRule.Pattern -> "Regex"
                is CollectionRule.Types -> "Types"
                is CollectionRule.Manual -> "Manual (empty)"
                else -> "Other"
            }
            Text(
                typeLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Remove condition",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        when (rule) {
            is CollectionRule.Duration -> DurationFields(rule, onChange)
            is CollectionRule.Pattern -> PatternFields(rule, onChange)
            is CollectionRule.Types -> TypesFields(rule, onChange)
            is CollectionRule.Manual -> Text(
                "Starts empty; add tracks later from any list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {}
        }
    }
}

@Composable
private fun DurationFields(
    rule: CollectionRule.Duration,
    onChange: (CollectionRule) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = rule.minMs?.let { (it / 60_000).toString() } ?: "",
            onValueChange = { v ->
                val min = v.filter { it.isDigit() }.toLongOrNull()?.let { it * 60_000L }
                onChange(rule.copy(minMs = min))
            },
            singleLine = true,
            label = { Text("Min (min)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = rule.maxMs?.let { (it / 60_000).toString() } ?: "",
            onValueChange = { v ->
                val max = v.filter { it.isDigit() }.toLongOrNull()?.let { it * 60_000L }
                onChange(rule.copy(maxMs = max))
            },
            singleLine = true,
            label = { Text("Max (min)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PatternFields(
    rule: CollectionRule.Pattern,
    onChange: (CollectionRule) -> Unit,
) {
    var fieldsExpanded by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = rule.pattern,
            onValueChange = { onChange(rule.copy(pattern = it)) },
            singleLine = true,
            label = { Text("Regex pattern") },
            placeholder = { Text("e.g. love|heart") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Box {
            TextButton(onClick = { fieldsExpanded = true }) {
                val summary = if (rule.fields.isEmpty()) "none"
                    else rule.fields.joinToString(", ") { it.name }
                Text("Fields: $summary")
            }
            DropdownMenu(
                expanded = fieldsExpanded,
                onDismissRequest = { fieldsExpanded = false },
            ) {
                CollectionRule.Pattern.Field.entries.forEach { field ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                (if (field in rule.fields) "✓ " else "    ") + field.name
                            )
                        },
                        onClick = {
                            val newFields = if (field in rule.fields)
                                rule.fields - field
                            else rule.fields + field
                            onChange(rule.copy(fields = newFields))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypesFields(
    rule: CollectionRule.Types,
    onChange: (CollectionRule) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ContentType.entries.forEach { type ->
            FilterChip(
                selected = type in rule.types,
                onClick = {
                    val newTypes = if (type in rule.types)
                        rule.types - type
                    else rule.types + type
                    onChange(CollectionRule.Types(newTypes))
                },
                label = { Text(type.name) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun AddConditionButton(onAdd: (CollectionRule) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("+ Add condition")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Duration (range)") },
                onClick = {
                    onAdd(CollectionRule.Duration())
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Regex (pattern)") },
                onClick = {
                    onAdd(CollectionRule.Pattern(""))
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Content type") },
                onClick = {
                    onAdd(CollectionRule.Types(emptySet()))
                    expanded = false
                },
            )
        }
    }
}
