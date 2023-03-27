package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.common.OptionalStringUtils;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneProvider;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneVerificationCodeProvider;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.MessageSenderService;
import org.jboss.logging.Logger;
import org.keycloak.Config.Scope;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.validation.Validation;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.ServiceUnavailableException;
import java.time.Instant;
import java.util.Optional;

public class DefaultPhoneProvider implements PhoneProvider {

    private static final Logger logger = Logger.getLogger(DefaultPhoneProvider.class);
    private final KeycloakSession session;
    private final String service;
    private final int tokenExpiresIn;
    private final int hourMaximum;

    private final Scope config;

    DefaultPhoneProvider(KeycloakSession session, Scope config) {
        this.session = session;
        this.config = config;


        this.service = session.listProviderIds(MessageSenderService.class)
                .stream().filter(s -> s.equals(config.get("service")))
                .findFirst().orElse(
                        session.listProviderIds(MessageSenderService.class)
                                .stream().findFirst().orElse(null)
                );

        if (Validation.isBlank(this.service)){
            logger.error("Message sender service provider not found!");
        }

        if (Validation.isBlank(config.get("service")))
            logger.warn("No message sender service provider specified! Default provider'" +
                this.service + "' will be used. You can use keycloak start param '--spi-phone-default-service' to specify a different one. ");

        this.tokenExpiresIn = config.getInt("tokenExpiresIn", 60);
        this.hourMaximum = config.getInt("hourMaximum",3);
    }

    @Override
    public void close() {
    }


    private PhoneVerificationCodeProvider getTokenCodeService() {
        return session.getProvider(PhoneVerificationCodeProvider.class);
    }

    private String getRealmName(){
        return session.getContext().getRealm().getName();
    }

    private Optional<String> getStringConfigValue(String configName){
        return OptionalStringUtils.ofBlank(OptionalStringUtils.ofBlank(config.get(getRealmName() + "-" + configName))
            .orElse(config.get(configName)));
    }

    private boolean getBooleanConfigValue(String configName, boolean defaultValue){
        Boolean result = config.getBoolean(getRealmName() + "-" + configName,null);
        if (result == null) {
            result = config.getBoolean(configName,defaultValue);
        }
        return result;
    }

    @Override
    public boolean isDuplicatePhoneAllowed() {
        return getBooleanConfigValue("duplicate-phone", false);
    }

    @Override
    public boolean validPhoneNumber() {
        return getBooleanConfigValue("valid-phone", true);
    }

    @Override
    public boolean canonicalizePhoneNumber() {
        return getBooleanConfigValue("canonicalize-phone-numbers", true);
    }

    @Override
    public Optional<String> defaultPhoneRegion() {
        return getStringConfigValue("default-phone-region");
    }



    @Override
    public int sendTokenCode(String phoneNumber,TokenCodeType type, String kind){
        logger.info("send code to:" + phoneNumber );

        if (getTokenCodeService().isAbusing(phoneNumber, type,hourMaximum)) {
            throw new ForbiddenException("You requested the maximum number of messages the last hour");
        }

        TokenCodeRepresentation ongoing = getTokenCodeService().ongoingProcess(phoneNumber, type);
        if (ongoing != null) {
            logger.info(String.format("No need of sending a new %s code for %s",type.getLabel(), phoneNumber));
            return (int) (ongoing.getExpiresAt().getTime() - Instant.now().toEpochMilli()) / 1000;
        }

        TokenCodeRepresentation token = TokenCodeRepresentation.forPhoneNumber(phoneNumber);

        try {
            session.getProvider(MessageSenderService.class, service).sendSmsMessage(type,phoneNumber,token.getCode(),tokenExpiresIn,kind);
            getTokenCodeService().persistCode(token, type, tokenExpiresIn);

            logger.info(String.format("Sent %s code to %s over %s",type.getLabel(), phoneNumber, service));

        } catch (MessageSendException e) {

            logger.error(String.format("Message sending to %s failed with %s: %s",
                    phoneNumber, e.getErrorCode(), e.getErrorMessage()));
            throw new ServiceUnavailableException("Internal server error");
        }

        return tokenExpiresIn;
    }

}
