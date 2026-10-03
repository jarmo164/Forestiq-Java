FROM quay.io/keycloak/keycloak:26.1.3

COPY forestiq-realm.json /opt/keycloak/data/import/forestiq-realm.json

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--import-realm"]
