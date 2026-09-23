#!/bin/bash
# Ativa NOPASSWD para o utilizador atual poder ser usado pelo agente.
# Uso: ./enable-sudo-nopasswd.sh
set -e

USER_NAME="$(whoami)"
SUDOERS_FILE="/etc/sudoers.d/${USER_NAME}"
RULE="${USER_NAME} ALL=(ALL) NOPASSWD:ALL"

echo "Utilizador: ${USER_NAME}"
echo "A criar: ${SUDOERS_FILE}"

sudo sh -c "echo '${RULE}' > '${SUDOERS_FILE}'"
sudo chmod 440 "${SUDOERS_FILE}"
sudo visudo -c

echo "A verificar 'sudo -n true'..."
if sudo -n true 2>/dev/null; then
  echo "OK: sudo sem password ativo."
else
  echo "FALHOU: sudo ainda pede password." >&2
  exit 1
fi

echo ""
echo "Para reverter depois, corre:"
echo "  sudo rm ${SUDOERS_FILE}"
