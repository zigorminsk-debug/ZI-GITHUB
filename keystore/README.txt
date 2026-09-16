ПОСТОЯННЫЙ КЛЮЧ ПОДПИСИ ZI GIT
================================
Файл:        zigit-release.jks  (PKCS12)
Alias:       zigit
Пароль:      ZigitRelease2026!   (и для хранилища, и для ключа)
Subject:     CN=ZI Git, O=ZI, C=BY
Алгоритм:    RSA 4096, SHA384withRSA
Действует:   16.09.2026 — 08.09.2056 (30 лет)
SHA-256 отпечаток сертификата:
  224a28684725f0a8312392c1fa28628feb6f64acd660e924b47a8192a3f13dfd
SHA-1:
  d761037ffb1a91352a0b34cd3e66c6d827da18bf
SHA-256 файла хранилища:
  bde5346f5137d68fe548272fc1284965935416cd4dfe2dde59f4422791c789b9

ВАЖНО
-----
1. Этот файл — единственный способ выпускать обновления, которые ставятся
   поверх уже установленного ZI Git. Потеряете ключ — старые версии больше
   не обновятся, приложение придётся удалять и ставить заново.
2. Обязательно сделайте резервную копию (например, в 1Password или на флешку)
   ВМЕСТЕ с паролем.
3. Пароль по умолчанию. Если планируете публиковать APK в интернете, смените его:
     keytool -importkeystore -srckeystore zigit-release.jks -srcstorepass 'ZigitRelease2026!' \
       -destkeystore zigit-new.jks -deststorepass 'НОВЫЙ_ПАРОЛЬ' \
       -srcalias zigit -destalias zigit -srckeypass 'ZigitRelease2026!' -destkeypass 'НОВЫЙ_ПАРОЛЬ'
   после чего собирайте так:
     ZIGIT_KEYSTORE=/путь/zigit-new.jks ZIGIT_KEYSTORE_PASS='НОВЫЙ_ПАРОЛЬ' ./build.sh

Сборка с этим ключом выполняется автоматически: ./build.sh (скрипт сам подхватит
файл keystore/zigit-release.jks и подпишет out/ZIGit.apk).
