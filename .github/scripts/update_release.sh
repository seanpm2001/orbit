owner="orbit"
repo="orbit"

tag=v$TAG_VERSION
GH_REPO="https://api.github.com/repos/$owner/$repo"
AUTH="Authorization: token $GITHUB_TOKEN"

# Commit all changed work
git commit -m "Release version $tag and update docs" --author="orbit-tools <orbit@ea.com>"

# Tag commit with the intended release tag (without the underscore)
git tag $tag
git push origin master --tags

# Get commit id
commitId=$(git rev-parse HEAD)
echo Commit Id: $commitId

# Read asset tags.
releaseResponse=$(curl -sH "$AUTH" "$GH_REPO/releases/tags/_$tag")

# Extract the release id
eval $(echo "$releaseResponse" | grep -m 1 "id.:" | grep -w id | tr : = | tr -cd '[[:alnum:]]=')
[ "$id" ] || { echo "Error: Failed to get release id for tag: $tag"; echo "$releaseResponse" | awk 'length($0)<100' >&2; exit 1; }

# Patch release with new commit Id and tag
curl -X PATCH -H "$AUTH" -H "Content-Type: application/json" $GH_REPO/releases/$id -d '{"tag_name": "$tag", "target_commitish": "$commitId"}'

git tag -d _$tag
git push origin :refs/tags/_$tag
git reset --hard
