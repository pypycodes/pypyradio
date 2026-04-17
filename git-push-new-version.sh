# 1. Grab the version from the file and prefix it with 'v'
vtag="v$(grep 'versionName =' app/build.gradle.kts | awk -F '"' '{print $2}')"
# 2. Add, Commit, and Tag automatically
git add . && git commit -m "feat: $vtag fixes" && git push
git tag "$vtag"
git push origin "$vtag"
echo "Successfully pushed and tagged $vtag"