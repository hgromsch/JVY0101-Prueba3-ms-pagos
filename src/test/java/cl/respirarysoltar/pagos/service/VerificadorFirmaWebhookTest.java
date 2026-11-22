package cl.respirarysoltar.pagos.service;

import cl.respirarysoltar.pagos.api.dto.WebhookRequest;
import cl.respirarysoltar.pagos.api.dto.WebhookRequest.EventoPasarela;
import cl.respirarysoltar.pagos.exception.FirmaInvalidaException;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificadorFirmaWebhookTest {

    private final VerificadorFirmaWebhook verificador = new VerificadorFirmaWebhook("secreto-de-prueba");
    private final WebhookRequest request = new WebhookRequest(UUID.randomUUID(), EventoPasarela.PAGO_CONFIRMADO);

    @Test
    void aceptaUnaFirmaValida() {
        String firma = HexFormat.of().formatHex(verificador.firmar(request.pagoId() + ":" + request.evento()));
        assertDoesNotThrow(() -> verificador.verificar(request, firma));
    }

    @Test
    void rechazaSinFirmaOConFirmaAlterada() {
        assertThrows(FirmaInvalidaException.class, () -> verificador.verificar(request, null));
        assertThrows(FirmaInvalidaException.class, () -> verificador.verificar(request, "abcd"));
        assertThrows(FirmaInvalidaException.class, () -> verificador.verificar(request, "zz-no-hex"));
    }

    @Test
    void sinSecretoConfiguradoNoExigeFirma() {
        VerificadorFirmaWebhook desarrollo = new VerificadorFirmaWebhook("");
        assertDoesNotThrow(() -> desarrollo.verificar(request, null));
    }
}
