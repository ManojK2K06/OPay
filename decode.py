import zlib
import base64
import sys

encoded = '0HiU1%kynOB^kTrdq=%Ulccx#Tdk]2uzb-te4D#T@U}^%/-?)ikZNJN3</)Le:dSWaBH>uBPJc}ApD{60mpeaKrQ]-qa-Rl0<>ZqJYM:Y/S>SyBXOAew<csk)og@XhGP::p&Dvd6Lj[Q)=lE^@reNukC43h>#fcT:1GANj7)lGD(p:<e{s-+)?w*r/E'

alphabet = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-:+=^!/*?&<>()[]{}@%$#'
decode_table = {c: i for i, c in enumerate(alphabet)}

def b85decode(s):
    out = bytearray()
    i = 0
    while i < len(s):
        chunk = s[i:i+5]
        block = 0
        for j, c in enumerate(chunk):
            block = block * 85 + decode_table[c]
        for j in range(5 - len(chunk)):
            block = block * 85 + 84
        
        output_bytes = len(chunk) - 1 if len(chunk) < 5 else 4
        for k in range((output_bytes - 1) * 8, -1, -8):
            out.append((block >> k) & 0xFF)
        i += 5
    return out

compressed = b85decode(encoded)
print('Compressed length:', len(compressed))

try:
    binary = zlib.decompress(compressed, -15)
    print('Binary length:', len(binary))
    print('Nonce:', binary[0:12].hex())
    print('Ciphertext:', binary[12:36].hex())
    print('Tag:', binary[36:52].hex())
    print('EphPub:', binary[52:85].hex())
    print('Sig:', binary[85:149].hex())
except Exception as e:
    print('Zlib error (raw):', e)

try:
    binary2 = zlib.decompress(compressed)
    print('Zlib decompressed OK with wrapper!')
except Exception as e:
    pass
