# Scripts de verificação (não-UI)

- `force_errors.py` — bateria de 24 erros contra um servidor local.
  Precisa de `websocket-client` (`pip install websocket-client`).
  ```bash
  PORT=8799 ./target/debug/flowtools-server &
  python3 scripts/force_errors.py http://127.0.0.1:8799
  ```
- `force_fileexists.py` — prova de ficheiro duplicado fim-a-fim.
  Precisa do servidor + PC client reais (o download não exige ecrã):
  ```bash
  PORT=8798 ./target/debug/flowtools-server &
  HOME=/tmp/pchome ./target/debug/flowtools-pc --server http://127.0.0.1:8798 &
  PCID=$(...)  # pc_id impresso no log do servidor
  python3 scripts/force_fileexists.py http://127.0.0.1:8798 "$PCID"
  ```
  Esperado: primeiro upload faz `file_done`, o segundo (mesmo nome)
  recebe `file_exists` e o disco mantém o primeiro conteúdo.
