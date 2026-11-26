# Controle TV

Controle remoto pelo celular para TVs com Android TV / Google TV (feito para uma SEMP).

## Instalar

No celular, abra este link e baixe o APK:

**https://github.com/vitoriablopes/controle-tv/releases/latest/download/ControleTV.apk**

Na hora de abrir o arquivo, o Android pede para permitir "instalar apps desconhecidos" pelo navegador. Pode permitir: é normal para apps que não vêm da Play Store. Se o Play Protect avisar, toque em "Instalar mesmo assim".

## Usar

1. Celular e TV na mesma rede Wi-Fi, com a TV ligada.
2. Abra o app → **Procurar TVs** → toque na sua TV.
3. Digite o código de 6 caracteres que aparece na tela da TV.

Pronto: o app guarda a TV e reconecta sozinho sempre que você abrir. Para desconectar de vez, vá em ⋮ → **Desconectar e esquecer esta TV**.

## O que tem

- Ligar/desligar, Entradas (HDMI etc.), Configurações da TV
- 5 atalhos de streaming personalizáveis (segure o botão para trocar)
- Setas com OK, Voltar, Início, Menu
- Volume +/−, Mudo, Play/Pausa (segurar setas e volume repete o comando)

## Como funciona

Usa o mesmo protocolo do app Google TV (portas 6466 e 6467, TLS com certificado do celular).
O código do protocolo fica em `app/src/main/java/br/vitoria/controletv/core`.
Cada envio para a branch `main` gera um APK novo pelo GitHub Actions (aba Releases).
