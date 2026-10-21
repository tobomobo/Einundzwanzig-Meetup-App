package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.CoroutineDispatcher
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.nostr.relay.RelayConnector
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Client
import space.einundzwanzig.meetup.data.identity.nip46.Nip46RelayOpener
import javax.inject.Inject

/** Production clients on the shared OkHttp [RelayConnector] with the spec deadlines. */
internal class DefaultNip46ClientFactory @Inject constructor(
    connector: RelayConnector,
    private val timeSource: TimeSource,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : Nip46ClientFactory {

    private val opener = Nip46RelayOpener.from(connector)

    override fun create(
        clientSecretKeyHex: String,
        relays: List<String>,
        remoteSignerPubkey: String?,
        onAuthUrl: (String) -> Unit,
    ): Nip46Client = Nip46Client(
        clientSecretKeyHex = clientSecretKeyHex,
        relays = relays,
        remoteSignerPubkey = remoteSignerPubkey,
        onAuthUrl = onAuthUrl,
        openRelay = opener,
        timeSource = timeSource,
        dispatcher = dispatcher,
    )
}
