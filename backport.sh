git reset HEAD~1
rm ./backport.sh
git cherry-pick 5bfdd32be3b10d0aa9517e05dd923c090d98ca7e
echo 'Resolve conflicts and force push this branch.\n\nTo backport translations run: bin/i18n/merge-translations <release-branch>'
