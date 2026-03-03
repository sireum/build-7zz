sudo sh -c 'echo -1 > /proc/sys/fs/binfmt_misc/cli'
sudo sh -c 'echo -1 > /proc/sys/fs/binfmt_misc/status'
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
rm -fR cosmocc* 7zip
curl -JLOs https://github.com/jart/cosmopolitan/releases/download/$COSMOCC_V/cosmocc-$COSMOCC_V.zip
mkdir cosmocc
cd cosmocc
unzip -qq ../cosmocc-$COSMOCC_V.zip
cd bin
ln -s cosmocc cc
ln -s cosmoc++ c++
cd ../..
export PATH=`pwd`/cosmocc/bin:$PATH
git clone https://github.com/ip7z/7zip
cd 7zip
git checkout $P7ZIP_V

# Apply patches (git apply fails loudly if hunks don't match)
git apply "$SCRIPT_DIR/patches/01-rename-last-error-remove-werror.patch"
git apply "$SCRIPT_DIR/patches/02-comspec-unix-extension.patch"

cd CPP/7zip/Bundles/Alone2
make -j -f makefile.gcc
cp _o/7zz $GITHUB_WORKSPACE
