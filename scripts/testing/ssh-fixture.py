#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Ephemeral loopback SSH/SFTP fixture. Requires asyncssh; never uses user keys.
Run with a private temporary directory, then pass its config.json path as
PDSH_SSH_TEST_CONFIG to swift test --filter SSHConnectionTests.
"""
import asyncio
import json
from pathlib import Path
import secrets
import sys
import asyncssh

async def main():
    directory = Path(sys.argv[1])
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    remote = directory / 'remote'
    remote.mkdir(exist_ok=True)
    host_key = asyncssh.generate_private_key('ssh-ed25519')
    client_key = asyncssh.generate_private_key('ssh-ed25519')
    rsa_key = asyncssh.generate_private_key('ssh-rsa', key_size=2048)
    password = secrets.token_urlsafe(24)

    class Server(asyncssh.SSHServer):
        def begin_auth(self, username): return True
        def password_auth_supported(self): return True
        def validate_password(self, username, supplied): return username == 'fixture' and supplied == password
        def public_key_auth_supported(self): return True
        def validate_public_key(self, username, key): return username == 'fixture' and key in (client_key.convert_to_public(), rsa_key.convert_to_public())

    async def process(session):
        # Fixed commands make the fixture deterministic and avoid a shell.
        if session.command == 'output':
            session.stdout.write('ssh stdout\n')
            session.stderr.write('ssh stderr\n')
            session.exit(7)
        elif session.command == 'wait':
            await asyncio.sleep(30)
            session.exit(0)
        elif session.command == 'large':
            session.stdout.write('x' * 100000)
            session.exit(0)
        else:
            session.stdout.write('connected\n')
            session.exit(0)

    server = await asyncssh.create_server(Server, '127.0.0.1', 0, server_host_keys=[host_key],
        process_factory=process, sftp_factory=lambda channel: asyncssh.SFTPServer(channel, chroot=str(remote)))
    class KeyOnlyServer(Server):
        def password_auth_supported(self): return False
    key_only = await asyncssh.create_server(KeyOnlyServer, '127.0.0.1', 0, server_host_keys=[host_key],
        process_factory=process)
    async def stall(reader, writer):
        try: await reader.read()
        finally: writer.close()
    stalled = await asyncio.start_server(stall, '127.0.0.1', 0)
    config = dict(keyOnlyPort=key_only.get_port(), stalledPort=stalled.sockets[0].getsockname()[1], port=server.get_port(), password=password, hostKey=host_key.export_public_key().decode().strip(),
        privateKey=client_key.export_private_key('openssh').decode(),
        encryptedKey=client_key.export_private_key('openssh', passphrase='fixture-passphrase', cipher_name='aes256-ctr', rounds=16).decode(),
        rsaKey=rsa_key.export_private_key('openssh').decode(), remote=str(remote), otherHostKey=asyncssh.generate_private_key('ssh-ed25519').export_public_key().decode().strip())
    path = directory / 'config.json'
    path.write_text(json.dumps(config))
    path.chmod(0o600)
    print('SSH fixture ready', flush=True)
    await asyncio.Future()

asyncio.run(main())
