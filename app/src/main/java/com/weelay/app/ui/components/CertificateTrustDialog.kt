package com.weelay.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weelay.app.relay.UntrustedCertificate
import java.text.DateFormat

/**
 * Asks whether to trust a certificate the system refused. The fingerprint is the part that
 * matters: the user is meant to compare it with the one their relay actually uses.
 */
@Composable
fun CertificateTrustDialog(
    certificate: UntrustedCertificate,
    onTrust: () -> Unit,
    onDismiss: () -> Unit
) {
    val warning = Color(0xFFE57373)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1F2C34),
        title = {
            Text(if (certificate.replacesPinned) "Certificate changed" else "Untrusted certificate")
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (certificate.replacesPinned) {
                        "${certificate.host}:${certificate.port} presented a different certificate " +
                            "from the one you trusted. That happens when the relay's certificate " +
                            "is renewed, but it is also what an interception would look like."
                    } else {
                        "Android doesn't trust the certificate from " +
                            "${certificate.host}:${certificate.port}. That's expected for a " +
                            "self-signed relay certificate; otherwise someone may be " +
                            "intercepting the connection."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Only trust it if this fingerprint matches your relay's:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp)
                )
                SelectionContainer {
                    Text(
                        certificate.sha256,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFE9EDEF),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    "Subject: ${certificate.subject}\n" +
                        "Issuer: ${certificate.issuer}\n" +
                        "Expires: ${DateFormat.getDateInstance().format(certificate.notAfter)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8696A0),
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    "On the relay host:  openssl x509 -in ~/.weechat/ssl/relay.pem " +
                        "-noout -fingerprint -sha256",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8696A0),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) { Text("Trust and connect", color = warning) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
